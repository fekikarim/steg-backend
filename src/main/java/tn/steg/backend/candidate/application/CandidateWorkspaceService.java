package tn.steg.backend.candidate.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.application.dto.CandidateAccountFilter;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateOverviewResponse;
import tn.steg.backend.candidate.application.dto.CandidateQueueResponse;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.repository.CertificateRepository;
import tn.steg.backend.common.application.ApplicationTimeZone;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.repository.ApplicationDocumentRepository;
import tn.steg.backend.document.domain.repository.InternshipDocumentRepository;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentReceiptRepository;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Candidates workspace for staff (AGENTS.md §5.1 / §6.2).
 *
 * <p>Three use cases, all resolved server-side:
 * <ul>
 *   <li>{@link #search} — one paged query with search, filters and sort; the
 *       Supervisor scope is derived from {@link SupervisionScopeService}, never
 *       accepted from the client.</li>
 *   <li>{@link #overview} — ONE call returning profile, supervisor,
 *       applications, internship, task summary, documents and
 *       certificate/receipt status (no client-side fan-out).</li>
 *   <li>{@link #delete} — soft delete (V41); refused with 409
 *       {@code CANDIDATE_HAS_DEPENDENCIES} while the candidate still has
 *       applications, internships, tasks or receipts.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CandidateWorkspaceService {

    /** Sort keys the API accepts; anything else falls back to createdAt DESC. */
    private static final Set<String> SORTABLE = Set.of(
            "firstName",
            "lastName",
            "email",
            "createdAt",
            "updatedAt");

    private static final int MAX_PAGE_SIZE = 100;

    private final CandidateRepository candidateRepository;
    private final InternshipApplicationRepository applicationRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;
    private final InternshipDocumentRepository internshipDocumentRepository;
    private final CertificateRepository certificateRepository;
    private final FinanceCaseRepository financeCaseRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final SupervisionScopeService supervisionScopeService;
    private final AuditService auditService;
    private final ApplicationTimeZone applicationTimeZone;

    // -------------------------------------------------------------------------
    // Queue (§5.1 list rule)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<CandidateQueueResponse> search(
            UserPrincipal actor,
            String search,
            String university,
            InternshipType type,
            UUID supervisorUserId,
            CandidateAccountFilter accountFilter,
            ApplicationStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {

        Pageable safe = sanitize(pageable);

        // Scope comes from SupervisionScopeService and ONLY from there (§3.2):
        // Admin = every candidate, Supervisor = assigned internships ∪ profiles
        // he manages (V44).
        boolean scoped = !supervisionScopeService.hasGlobalAccess(actor);
        List<UUID> candidateIds = List.of();
        if (scoped) {
            candidateIds = supervisionScopeService.supervisedCandidateIds(actor);
            if (candidateIds.isEmpty()) {
                return Page.empty(safe);
            }
        }

        String pattern = search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        // Stored lower-case; lower-casing here also keeps a non-null text
        // parameter typed as text in Postgres (a NULL inside LOWER() is not).
        String universityName = university == null || university.isBlank()
                ? null
                : university.trim().toLowerCase(Locale.ROOT);
        Boolean accountActive = accountFilter == null
                ? null
                : accountFilter == CandidateAccountFilter.ACTIVE;
        // Calendar-day bounds resolve in the application time zone
        // (default Africa/Tunis), never UTC: a profile created at 00:10
        // Tunis time belongs to "today" for a Tunis user even though it is
        // still "yesterday" in UTC.
        Instant createdFrom = from == null ? null : applicationTimeZone.startOfDay(from);
        Instant createdToExclusive = to == null ? null : applicationTimeZone.startOfNextDay(to);

        Page<Candidate> page = candidateRepository.searchStaffCandidates(
                scoped,
                candidateIds,
                universityName,
                pattern,
                accountActive,
                status,
                type,
                supervisorUserId,
                AssignmentStatus.ACTIVE,
                createdFrom,
                createdToExclusive,
                safe);

        return new PageImpl<>(enrich(page.getContent()), safe, page.getTotalElements());
    }

    /** Batches the per-row enrichment (application count/status, supervisor) in 3 queries for the whole page. */
    private List<CandidateQueueResponse> enrich(List<Candidate> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Candidate::getId).toList();

        Map<UUID, Long> applicationCounts = new HashMap<>();
        Map<UUID, ApplicationStatus> latestStatus = new HashMap<>();
        applicationRepository.findByCandidateIdIn(ids).stream()
                .filter(a -> a.getCandidate() != null)
                .sorted(Comparator.comparing(
                        tn.steg.backend.application.domain.model.InternshipApplication::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(a -> {
                    UUID cid = a.getCandidate().getId();
                    applicationCounts.merge(cid, 1L, Long::sum);
                    // Descending order: putIfAbsent keeps the status of the most recent application.
                    latestStatus.putIfAbsent(cid, a.getStatus());
                });

        Map<UUID, Internship> latestInternship = new HashMap<>();
        List<Internship> internships = internshipRepository.findByCandidateIdIn(ids);
        internships.stream()
                .filter(i -> i.getCandidate() != null)
                .sorted(Comparator.comparing(Internship::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(i -> latestInternship.putIfAbsent(i.getCandidate().getId(), i));

        Map<UUID, User> supervisors = new HashMap<>();
        if (!latestInternship.isEmpty()) {
            List<UUID> internshipIds = latestInternship.values().stream().map(Internship::getId).toList();
            Map<UUID, User> activeSupervisor = new HashMap<>();
            assignmentRepository.findByInternshipIdInAndStatus(internshipIds, AssignmentStatus.ACTIVE)
                    .forEach(a -> {
                        User sup = supervisorOf(a);
                        if (sup != null) {
                            activeSupervisor.put(a.getInternship().getId(), sup);
                        }
                    });
            latestInternship.forEach((candidateId, internship) -> {
                User sup = activeSupervisor.getOrDefault(internship.getId(), internship.getSupervisorUser());
                if (sup != null) {
                    supervisors.put(candidateId, sup);
                }
            });
        }

        List<CandidateQueueResponse> content = new ArrayList<>(rows.size());
        for (Candidate c : rows) {
            // Supervisor of the row: the internship assignment when there is one,
            // else the staff member who created/ manages the profile (V44). A
            // staff-created candidate has no internship yet and must still show
            // who manages it (§5.1).
            User supervisor = supervisors.get(c.getId());
            if (supervisor == null) {
                supervisor = c.getManagedBy();
            }
            content.add(CandidateQueueResponse.from(
                    c,
                    applicationCounts.getOrDefault(c.getId(), 0L),
                    latestStatus.get(c.getId()),
                    supervisor));
        }
        return content;
    }

    // -------------------------------------------------------------------------
    // Detail aggregate (§5.1 "one backend call")
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CandidateOverviewResponse overview(UserPrincipal actor, UUID candidateId) {
        Candidate candidate = findVisibleOrThrow(actor, candidateId);

        boolean isAdmin = actor.hasRole("ADMIN");
        String nationalId = isAdmin ? candidate.getNationalIdEncrypted() : null;
        if (nationalId != null) {
            // E1.5: every entitled CIN read is audited (no value logged).
            auditService.log("CANDIDATE_CIN_ACCESSED", "Candidate", candidate.getId(), null,
                    Map.of("disclosedTo", "staff-overview"), actor.getId(), null,
                    AuditService.primaryRole(actor.getRoles()), null);
        }

        List<tn.steg.backend.application.domain.model.InternshipApplication> applications =
                applicationRepository.findByCandidateId(candidateId).stream()
                        .sorted(Comparator.comparing(
                                tn.steg.backend.application.domain.model.InternshipApplication::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList();

        List<Internship> internships = internshipRepository.findByCandidateId(candidateId).stream()
                .sorted(Comparator.comparing(Internship::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Internship latest = internships.isEmpty() ? null : internships.get(0);

        User supervisor = null;
        if (latest != null) {
            supervisor = assignmentRepository.findByInternshipIdAndStatus(latest.getId(), AssignmentStatus.ACTIVE)
                    .map(this::supervisorOf)
                    .orElse(latest.getSupervisorUser());
        }

        List<Task> tasks = internships.isEmpty()
                ? List.of()
                : taskRepository.findByInternshipIdIn(
                        internships.stream().map(Internship::getId).toList(), Pageable.unpaged()).getContent();

        List<CandidateOverviewResponse.DocumentInfo> documents = new ArrayList<>();
        Set<UUID> seenDocuments = new HashSet<>();
        for (var application : applications) {
            for (var link : applicationDocumentRepository.findByApplicationId(application.getId())) {
                if (seenDocuments.add(link.getDocument().getId())) {
                    documents.add(CandidateOverviewResponse.from(link.getDocument(), "APPLICATION"));
                }
            }
        }
        for (Internship internship : internships) {
            for (var link : internshipDocumentRepository.findByInternshipId(internship.getId())) {
                if (seenDocuments.add(link.getDocument().getId())) {
                    documents.add(CandidateOverviewResponse.from(link.getDocument(), "INTERNSHIP"));
                }
            }
        }

        Certificate certificate = latest == null
                ? null
                : certificateRepository.findByInternshipId(latest.getId()).stream()
                        .max(Comparator.comparing(Certificate::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null);

        PaymentReceipt receipt = null;
        for (Internship internship : internships) {
            receipt = financeCaseRepository.findByInternshipId(internship.getId())
                    .flatMap(financeCase -> paymentReceiptRepository.findByFinanceCaseId(financeCase.getId()))
                    .orElse(null);
            if (receipt != null) {
                break;
            }
        }

        return new CandidateOverviewResponse(
                CandidateDetailResponse.from(candidate, nationalId),
                CandidateOverviewResponse.supervisorOf(supervisor),
                applications.stream().map(CandidateOverviewResponse::from).toList(),
                CandidateOverviewResponse.from(latest),
                CandidateOverviewResponse.from(tasks),
                documents,
                CandidateOverviewResponse.from(certificate),
                CandidateOverviewResponse.from(receipt));
    }

    // -------------------------------------------------------------------------
    // Soft delete (§5.1)
    // -------------------------------------------------------------------------

    @Transactional
    public void delete(UserPrincipal actor, UUID candidateId) {
        Candidate candidate = findVisibleOrThrow(actor, candidateId);

        List<Internship> internships = internshipRepository.findByCandidateId(candidateId);
        List<String> blockers = new ArrayList<>();
        if (applicationRepository.existsByCandidateId(candidateId)) {
            blockers.add("applications");
        }
        if (!internships.isEmpty()) {
            blockers.add("internships");
            List<UUID> internshipIds = internships.stream().map(Internship::getId).toList();
            if (!taskRepository.findByInternshipIdIn(internshipIds, Pageable.unpaged()).isEmpty()) {
                blockers.add("tasks");
            }
            for (Internship internship : internships) {
                boolean hasReceipt = financeCaseRepository.findByInternshipId(internship.getId())
                        .flatMap(financeCase -> paymentReceiptRepository.findByFinanceCaseId(financeCase.getId()))
                        .isPresent();
                if (hasReceipt) {
                    blockers.add("receipts");
                    break;
                }
            }
        }
        if (!blockers.isEmpty()) {
            throw new ConflictException("CANDIDATE_HAS_DEPENDENCIES",
                    "Candidate still has " + String.join(", ", blockers)
                            + " attached: close or reassign them before deleting the profile.");
        }

        // Soft delete = tombstone: the row survives for audit/history but is
        // hidden everywhere, and BOTH identity anchors are released so the same
        // person can register again (V42 / audit assumption #13):
        //   * user_id -> NULL frees the account (users can re-register);
        //   * the CIN becomes free because uniqueness is live-only.
        candidate.setDeletedAt(Instant.now());
        candidate.setUser(null);
        candidateRepository.save(candidate);
        auditService.log("CANDIDATE_SOFT_DELETED", "Candidate", candidate.getId(), null,
                Map.of("deleted", true), actor.getId(), null,
                AuditService.primaryRole(actor.getRoles()), null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Visible = not soft-deleted AND inside the actor's scope (Admin: global, Supervisor: own candidates). */
    private Candidate findVisibleOrThrow(UserPrincipal actor, UUID candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + candidateId));
        if (!supervisionScopeService.supervisesCandidate(actor, candidateId)) {
            // Out of scope → 404, never 403: no existence leak (§3.4).
            throw new ResourceNotFoundException("Candidate not found: " + candidateId);
        }
        return candidate;
    }

    /** User-backed supervisor of an assignment, with the legacy employee link as fallback. */
    private User supervisorOf(tn.steg.backend.internship.domain.model.InternshipAssignment assignment) {
        if (assignment.getSupervisorUser() != null) {
            return assignment.getSupervisorUser();
        }
        return assignment.getSupervisor() != null ? assignment.getSupervisor().getUser() : null;
    }

    /** Clamps the page size and drops sort properties the API does not expose. */
    private static Pageable sanitize(Pageable pageable) {
        Pageable source = pageable != null
                ? pageable
                : PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        int size = Math.min(Math.max(source.getPageSize(), 1), MAX_PAGE_SIZE);
        Sort safeSort = Sort.by(source.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        if (safeSort.isUnsorted()) {
            safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return PageRequest.of(source.getPageNumber(), size, safeSort);
    }
}
