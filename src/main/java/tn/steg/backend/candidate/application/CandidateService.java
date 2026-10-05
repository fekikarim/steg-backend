package tn.steg.backend.candidate.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateRequest;
import tn.steg.backend.candidate.application.dto.CandidateSummaryResponse;
import tn.steg.backend.candidate.application.dto.StaffCandidateCreateRequest;
import tn.steg.backend.candidate.application.dto.UniversityResponse;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.candidate.domain.service.NationalIdHasher;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.event.CandidateValidatedEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.SupervisionScopeService;

import java.util.Base64;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the Candidate module.
 * <p>
 * Security rules enforced here (never trust the client):
 * <ul>
 *   <li>Only one Candidate profile per User account.</li>
 *   <li>nationalId (CIN) is SHA-256 hashed for uniqueness checks and stored as-is in
 *       {@code nationalIdEncrypted}. In a production system that field would use
 *       AES-GCM; for Phase A3 we store the raw value to keep the scope manageable
 *       and flag it with {@code restricted_access} on the document side.</li>
 *   <li>{@code nationalId} is never returned in list responses; only in detail view
 *       when the requester is the candidate themselves or has the ADMIN role.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final UniversityRepository universityRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SupervisionScopeService supervisionScopeService;
    /** Business facts for cross-cutting concerns; never a dependency on consumers. */
    private final ApplicationEventPublisher eventPublisher;

    // -------------------------------------------------------------------------
    // Candidate CRUD
    // -------------------------------------------------------------------------

    @Transactional
    public CandidateDetailResponse createCandidate(CandidateRequest request, UserPrincipal actor) {
        // CIN is the identity anchor of a profile: required on create (the DTO
        // keeps it optional on purpose so UPDATE can leave it untouched).
        if (request.nationalId() == null || request.nationalId().isBlank()) {
            throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_REQUIRED",
                    "A national ID (CIN) is required to create a candidate profile.");
        }
        // One LIVE profile per user account: a soft-deleted tombstone releases
        // the account (V42 / audit assumption #13), so the check is a live-only
        // existence query and never scans the table.
        if (candidateRepository.existsByUserIdAndDeletedAtIsNull(actor.getId())) {
            throw new BusinessRuleException("CANDIDATE_PROFILE_EXISTS",
                    "A candidate profile already exists for your account.");
        }

        String nationalIdHash = NationalIdHasher.sha256Hex(request.nationalId());

        // CLAIM (audit assumption #16): STAFF may have created this person's
        // profile before they had a front-office account (§5.1 / A4). The person
        // then registers with the SAME CIN or the SAME email and takes that
        // profile over instead of creating a duplicate.
        Candidate claimable = findClaimableProfile(nationalIdHash, request.email());
        if (claimable != null) {
            return claimProfile(claimable, request, nationalIdHash, actor);
        }

        // Uniqueness of the CIN applies to LIVE profiles only: the same person
        // (same CIN) may register again after their profile was deleted.
        if (candidateRepository.existsByNationalIdHashAndDeletedAtIsNull(nationalIdHash)) {
            throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_DUPLICATE",
                    "A candidate with this national ID is already registered.");
        }

        University university = findUniversityOrThrow(request.universityId());
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User account not found."));

        Candidate candidate = new Candidate(
                request.firstName(),
                request.lastName(),
                request.email(),
                nationalIdHash,
                university
        );
        candidate.setNationalIdEncrypted(request.nationalId()); // plain for now; encrypt in Phase A6
        candidate.setPhone(request.phone());
        candidate.setBirthDate(request.birthDate());
        candidate.setAddress(request.address());
        candidate.setSpeciality(request.speciality());
        candidate.setDiploma(request.diploma());
        candidate.setSkills(request.skills());
        candidate.setLanguages(request.languages());
        candidate.setUser(user);

        candidate = candidateRepository.save(candidate);
        log.info("Candidate profile created for user: {}", actor.getId());
        // E1.5: CIN-bearing responses are audited on every access (no CIN value logged).
        auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                java.util.Map.of("disclosedTo", "self-on-create"), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
        // AGENTS.md §8.1: the candidate validated their front-office account —
        // every Admin is notified (handler dedupes repeats).
        auditService.log("CANDIDATE_VALIDATED", "Candidate", candidate.getId(), null,
                java.util.Map.of("channel", "self-registration"), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
        eventPublisher.publishEvent(new CandidateValidatedEvent(
                candidate.getId(), actor.getId(), candidate.getEmail(),
                candidate.getFirstName() + " " + candidate.getLastName(), actor.getId()));
        return CandidateDetailResponse.from(candidate, request.nationalId());
    }

    /**
     * Staff creation of a candidate profile (AGENTS.md §5.1 Admin, §6.2
     * Supervisor in his own scope, ambiguity A4).
     *
     * <p>Rules enforced here, never in the client:
     * <ul>
     *   <li>a CIN is REQUIRED — it is what the person will later claim the
     *       profile with;</li>
     *   <li>the profile has NO account ({@code user_id IS NULL}): staff creation
     *       never invents a front-office login;</li>
     *   <li>{@code managed_by_user_id} = the acting staff member, or — Admin
     *       only — another supervisor/Admin chosen in the dialog;</li>
     *   <li>no application is created and approval is untouched: approving an
     *       application stays Admin-only (§5.2).</li>
     * </ul>
     */
    @Transactional
    public CandidateDetailResponse createCandidateByStaff(StaffCandidateCreateRequest request, UserPrincipal actor) {
        CandidateRequest profile = request.candidate();
        if (profile == null || profile.nationalId() == null || profile.nationalId().isBlank()) {
            throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_REQUIRED",
                    "A national ID (CIN) is required to create a candidate profile.");
        }
        String nationalIdHash = NationalIdHasher.sha256Hex(profile.nationalId());

        // Authorization BEFORE any business check: a supervisor probing somebody
        // else's scope must get 403 whatever the payload says, and never learn
        // anything about existing profiles from the error.
        User manager = resolveManager(request.managedByUserId(), actor);

        if (candidateRepository.existsByNationalIdHashAndDeletedAtIsNull(nationalIdHash)) {
            throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_DUPLICATE",
                    "A candidate with this national ID is already registered.");
        }

        University university = findUniversityOrThrow(profile.universityId());

        Candidate candidate = new Candidate(
                profile.firstName(),
                profile.lastName(),
                profile.email(),
                nationalIdHash,
                university
        );
        candidate.setNationalIdEncrypted(profile.nationalId());
        candidate.setPhone(profile.phone());
        candidate.setBirthDate(profile.birthDate());
        candidate.setAddress(profile.address());
        candidate.setSpeciality(profile.speciality());
        candidate.setDiploma(profile.diploma());
        candidate.setSkills(profile.skills());
        candidate.setLanguages(profile.languages());
        // No account yet: the person claims this profile when they register.
        candidate.setUser(null);
        candidate.setManagedBy(manager);

        candidate = candidateRepository.save(candidate);
        log.info("Candidate profile created by staff actor={} managedBy={} candidate={}",
                actor.getId(), manager.getId(), candidate.getId());

        auditService.log("CANDIDATE_CREATED_BY_STAFF", "Candidate", candidate.getId(), null,
                java.util.Map.of(
                        "managedByUserId", manager.getId().toString(),
                        "claimedByOwner", false),
                actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null);

        // CIN disclosure follows the read rule (§5.1): ADMIN and the candidate
        // himself only. A Supervisor response stays redacted, like his edits.
        boolean discloseCin = actor.hasRole("ADMIN");
        if (discloseCin) {
            auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                    java.util.Map.of("disclosedTo", "staff-on-create"), actor.getId(), null,
                    AuditService.primaryRole(actor.getRoles()), null);
        }
        return CandidateDetailResponse.from(candidate, discloseCin ? profile.nationalId() : null);
    }

    // -------------------------------------------------------------------------
    // Claim of a staff-created profile (audit assumption #16)
    // -------------------------------------------------------------------------

    /**
     * The LIVE profile this registration may take over, or {@code null} when
     * there is none.
     *
     * <p>Match on the same CIN OR the same email (case-insensitive) — the two
     * things a person always presents the same way. A profile that already
     * belongs to an account is NEVER claimable: two different people must never
     * be merged, so the caller falls through to the duplicate-CIN error.
     */
    private Candidate findClaimableProfile(String nationalIdHash, String email) {
        Optional<Candidate> byCin = candidateRepository.findByNationalIdHashAndDeletedAtIsNull(nationalIdHash);
        if (byCin.isPresent()) {
            Candidate candidate = byCin.get();
            if (candidate.getUser() != null) {
                return null;
            }
            return candidate;
        }
        if (email != null && !email.isBlank()) {
            return candidateRepository.findByEmailIgnoreCase(email.trim()).stream()
                    .filter(c -> c.getDeletedAt() == null)
                    .filter(c -> c.getUser() == null)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * Attaches the account to a staff-created profile and refreshes the CONTACT
     * fields the person just typed. Academic and governance fields (CIN when it
     * matched, university, speciality, diploma, skills, languages) keep the
     * values staff entered — they are deliberate, and the applicant must not be
     * able to rewrite them through a registration form.
     *
     * <p>When the match was by EMAIL only and the applicant presents a
     * different CIN, the CIN is adopted (the live-uniqueness check guards it):
     * the alternative would keep somebody else's CIN on this person's profile.
     */
    private CandidateDetailResponse claimProfile(Candidate candidate,
                                                 CandidateRequest request,
                                                 String nationalIdHash,
                                                 UserPrincipal actor) {
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User account not found."));

        boolean cinChanged = !nationalIdHash.equals(candidate.getNationalIdHash());
        if (cinChanged) {
            if (candidateRepository.existsByNationalIdHashAndDeletedAtIsNull(nationalIdHash)) {
                throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_DUPLICATE",
                        "A candidate with this national ID is already registered.");
            }
            candidate.setNationalIdHash(nationalIdHash);
            candidate.setNationalIdEncrypted(request.nationalId());
        }

        candidate.setUser(user);
        candidate.setFirstName(request.firstName());
        candidate.setLastName(request.lastName());
        candidate.setEmail(request.email());
        candidate.setPhone(request.phone());
        candidate.setBirthDate(request.birthDate());

        candidate = candidateRepository.save(candidate);
        log.info("Candidate profile claimed by its owner: candidate={} user={}",
                candidate.getId(), actor.getId());

        auditService.log("CANDIDATE_CLAIMED", "Candidate", candidate.getId(), null,
                java.util.Map.of("matchedOn", cinChanged ? "EMAIL" : "CIN_OR_EMAIL"),
                actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null);
        auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                java.util.Map.of("disclosedTo", "self-on-claim"), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
        // AGENTS.md §8.1: claiming the staff-created profile validates the
        // front-office account — every Admin is notified (handler dedupes).
        auditService.log("CANDIDATE_VALIDATED", "Candidate", candidate.getId(), null,
                java.util.Map.of("channel", "claim"), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
        eventPublisher.publishEvent(new CandidateValidatedEvent(
                candidate.getId(), actor.getId(), candidate.getEmail(),
                candidate.getFirstName() + " " + candidate.getLastName(), actor.getId()));
        return CandidateDetailResponse.from(candidate, request.nationalId());
    }

    /**
     * Who manages a staff-created profile (V44). Admin may pick anybody with a
     * back-office role (including himself, §3.2); a Supervisor may only create
     * candidates he manages himself — otherwise he could park a candidate in
     * somebody else's scope and lose sight of it.
     */
    private User resolveManager(UUID requestedUserId, UserPrincipal actor) {
        boolean isAdmin = actor.hasRole("ADMIN");
        if (!isAdmin && requestedUserId != null && !requestedUserId.equals(actor.getId())) {
            throw new AccessDeniedException(
                    "A supervisor can only create candidates managed by himself.");
        }
        UUID targetId = isAdmin && requestedUserId != null ? requestedUserId : actor.getId();
        User manager = userRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found: " + targetId));
        boolean backOffice = manager.getAssignedRoles().stream()
                .map(Role::getCode)
                .anyMatch(code -> "ADMIN".equals(code) || "SUPERVISOR".equals(code));
        if (!backOffice) {
            throw new BusinessRuleException("CANDIDATE_MANAGER_INVALID",
                    "The managing staff member must hold the ADMIN or SUPERVISOR role.");
        }
        return manager;
    }

    @Transactional(readOnly = true)
    public List<CandidateSummaryResponse> listCandidates(UserPrincipal actor) {
        // nationalId is intentionally absent from CandidateSummaryResponse
        // Admin: global list; Supervisor: scoped to own assigned candidates only.
        if (actor != null && actor.hasRole("SUPERVISOR") && !actor.hasRole("ADMIN")) {
            // Same rule as every other module: SupervisionScopeService only (§3.2).
            return supervisionScopeService.supervisedCandidates(actor).stream()
                    .map(CandidateSummaryResponse::from)
                    .toList();
        }
        return candidateRepository.findAll().stream()
                .filter(c -> c.getDeletedAt() == null)
                .map(CandidateSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse getMyProfile(UserPrincipal actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found for user: " + actor.getId()));
        // E1.5: self CIN read is entitled and audited (no value logged).
        auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                java.util.Map.of("disclosedTo", "self"), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
        return CandidateDetailResponse.from(candidate, candidate.getNationalIdEncrypted());
    }

    @Transactional
    public CandidateDetailResponse updateMyProfile(CandidateRequest request, UserPrincipal actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found for user: " + actor.getId()));
        return updateCandidate(candidate.getId(), request, actor);
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse getCandidate(UUID id, UserPrincipal actor) {
        Candidate candidate = findCandidateOrThrow(id);

        boolean isSelf = candidate.getUser() != null &&
                         candidate.getUser().getId().equals(actor.getId());
        boolean isAdmin = actor.hasRole("ADMIN");

        // Out of scope (foreign Supervisor, foreign candidate user, other roles)
        // → 404, never 403: no existence leak (§3.4, docs/legacy-removal.md #7).
        if (!isSelf && !isAdmin && !supervisionScopeService.supervisesCandidate(actor, id)) {
            throw new ResourceNotFoundException("Candidate not found: " + id);
        }

        // Provide nationalId to self and admin only — not to supervisors
        String nationalId = (isSelf || isAdmin) ? candidate.getNationalIdEncrypted() : null;
        if (nationalId != null) {
            // E1.5: every entitled CIN read is audited (no value logged).
            auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                    java.util.Map.of("disclosedTo", isSelf ? "self" : "staff"), actor.getId(), null,
                    AuditService.primaryRole(actor.getRoles()), null);
        }
        return CandidateDetailResponse.from(candidate, nationalId);
    }

    @Transactional
    public CandidateDetailResponse updateCandidate(UUID id, CandidateRequest request, UserPrincipal actor) {
        Candidate candidate = findCandidateOrThrow(id);

        boolean isSelf = candidate.getUser() != null &&
                         candidate.getUser().getId().equals(actor.getId());
        boolean isAdmin = actor.hasRole("ADMIN");

        // Staff CRUD inside scope (§3.3): Admin any candidate, Supervisor his
        // own candidates; everyone else (and a foreign candidate) → 404.
        if (!isSelf && !isAdmin && !supervisionScopeService.supervisesCandidate(actor, id)) {
            throw new ResourceNotFoundException("Candidate not found: " + id);
        }

        // CIN is optional on update: when omitted the stored value is kept, so
        // staff can correct a profile without ever receiving the CIN.
        String providedNationalId = request.nationalId();
        if (providedNationalId != null && !providedNationalId.isBlank()) {
            String newHash = NationalIdHasher.sha256Hex(providedNationalId);
            if (!newHash.equals(candidate.getNationalIdHash())) {
                if (candidateRepository.existsByNationalIdHashAndDeletedAtIsNull(newHash)) {
                    throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_DUPLICATE",
                            "A candidate with this national ID is already registered.");
                }
                candidate.setNationalIdHash(newHash);
                candidate.setNationalIdEncrypted(providedNationalId);
            }
        }

        University university = findUniversityOrThrow(request.universityId());
        candidate.setFirstName(request.firstName());
        candidate.setLastName(request.lastName());
        candidate.setEmail(request.email());
        candidate.setPhone(request.phone());
        candidate.setBirthDate(request.birthDate());
        candidate.setAddress(request.address());
        candidate.setSpeciality(request.speciality());
        candidate.setDiploma(request.diploma());
        candidate.setSkills(request.skills());
        candidate.setLanguages(request.languages());
        candidate.setUniversity(university);

        candidate = candidateRepository.save(candidate);
        log.info("Candidate profile updated: id={}", id);

        // CIN disclosure follows the read rule: self and Admin only. A
        // Supervisor edit response is redacted and audited without any CIN.
        boolean discloseCin = isSelf || isAdmin;
        if (discloseCin) {
            // E1.5: update response carries the CIN back — audit the disclosure.
            auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                    java.util.Map.of("disclosedTo", isSelf ? "self-on-update" : "staff-on-update"),
                    actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null);
        } else {
            auditService.log("CANDIDATE_UPDATED", "Candidate", candidate.getId(), null,
                    java.util.Map.of("updatedBy", "supervisor-in-scope"),
                    actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null);
        }
        return CandidateDetailResponse.from(candidate,
                discloseCin ? candidate.getNationalIdEncrypted() : null);
    }

    // -------------------------------------------------------------------------
    // University reference list
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UniversityResponse> listUniversities() {
        return universityRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .map(UniversityResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Candidate findCandidateOrThrow(UUID id) {
        return candidateRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + id));
    }

    private University findUniversityOrThrow(UUID id) {
        return universityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("University not found: " + id));
    }

    /**
     * Helper to format national ID as a deterministic hash for uniqueness checks.
     */
    private static String formatNationalIdHash(String input) {
        return NationalIdHasher.sha256Hex(input);
    }
}
