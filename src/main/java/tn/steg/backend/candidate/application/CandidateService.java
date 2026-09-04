package tn.steg.backend.candidate.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateRequest;
import tn.steg.backend.candidate.application.dto.CandidateSummaryResponse;
import tn.steg.backend.candidate.application.dto.UniversityResponse;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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
 *       when the requester is the candidate themselves or has ADMIN/HR role.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final UniversityRepository universityRepository;
    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // Candidate CRUD
    // -------------------------------------------------------------------------

    @Transactional
    public CandidateDetailResponse createCandidate(CandidateRequest request, UserPrincipal actor) {
        // One profile per user account
        candidateRepository.findAll().stream()
                .filter(c -> c.getUser() != null && c.getUser().getId().equals(actor.getId()))
                .findAny()
                .ifPresent(c -> {
                    throw new BusinessRuleException("CANDIDATE_PROFILE_EXISTS",
                            "A candidate profile already exists for your account.");
                });

        String nationalIdHash = sha256Hex(request.nationalId());
        if (candidateRepository.existsByNationalIdHash(nationalIdHash)) {
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
        return CandidateDetailResponse.from(candidate, request.nationalId());
    }

    @Transactional(readOnly = true)
    public List<CandidateSummaryResponse> listCandidates() {
        // nationalId is intentionally absent from CandidateSummaryResponse
        return candidateRepository.findAll().stream()
                .map(CandidateSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse getMyProfile(UserPrincipal actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found for user: " + actor.getId()));
        return CandidateDetailResponse.from(candidate, candidate.getNationalIdEncrypted());
    }

    @Transactional
    public CandidateDetailResponse updateMyProfile(CandidateRequest request, UserPrincipal actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found for user: " + actor.getId()));
        return updateCandidate(candidate.getId(), request, actor);
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse getCandidate(UUID id, UserPrincipal actor) {
        Candidate candidate = findCandidateOrThrow(id);

        boolean isSelf = candidate.getUser() != null &&
                         candidate.getUser().getId().equals(actor.getId());
        boolean isStaff = actor.hasRole("ADMIN") || actor.hasRole("HR");

        if (!isSelf && !isStaff) {
            throw new AccessDeniedException("You do not have permission to view this candidate profile.");
        }

        // Provide nationalId to self and staff only
        String nationalId = (isSelf || isStaff) ? candidate.getNationalIdEncrypted() : null;
        return CandidateDetailResponse.from(candidate, nationalId);
    }

    @Transactional
    public CandidateDetailResponse updateCandidate(UUID id, CandidateRequest request, UserPrincipal actor) {
        Candidate candidate = findCandidateOrThrow(id);

        boolean isSelf = candidate.getUser() != null &&
                         candidate.getUser().getId().equals(actor.getId());
        boolean isStaff = actor.hasRole("ADMIN") || actor.hasRole("HR");

        if (!isSelf && !isStaff) {
            throw new AccessDeniedException("You do not have permission to update this candidate profile.");
        }

        // If nationalId changed, recheck uniqueness
        String newHash = sha256Hex(request.nationalId());
        if (!newHash.equals(candidate.getNationalIdHash())) {
            if (candidateRepository.existsByNationalIdHash(newHash)) {
                throw new BusinessRuleException("CANDIDATE_NATIONAL_ID_DUPLICATE",
                        "A candidate with this national ID is already registered.");
            }
            candidate.setNationalIdHash(newHash);
            candidate.setNationalIdEncrypted(request.nationalId());
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
        return CandidateDetailResponse.from(candidate, candidate.getNationalIdEncrypted());
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
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + id));
    }

    private University findUniversityOrThrow(UUID id) {
        return universityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("University not found: " + id));
    }

    /**
     * Deterministic SHA-256 hex digest used as the uniqueness token for nationalId.
     * In Phase A6 this will be replaced by HMAC-SHA256 with a server-side key.
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
