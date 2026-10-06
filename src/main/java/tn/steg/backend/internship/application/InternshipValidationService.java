package tn.steg.backend.internship.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.ai.domain.client.DocIntelClient;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.event.DocumentRejectedEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.DeliverableStatus;
import tn.steg.backend.companion.domain.model.DeliverableVersion;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskCompletionPolicy;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.DeliverableVersionRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.finance.application.FinanceService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.dto.ValidationDetailResponse;
import tn.steg.backend.internship.application.dto.ValidationQueueQuery;
import tn.steg.backend.internship.application.dto.ValidationQueueRow;
import tn.steg.backend.internship.application.dto.VerificationResultResponse;
import tn.steg.backend.internship.domain.model.DocumentValidationDecision;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.model.ValidationDecision;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;
import tn.steg.backend.internship.domain.model.VerificationRun;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.internship.domain.repository.ValidationRepository;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * S7 internship validation (AGENTS.md §5.11): Admin-only queue, detail,
 * advisory python-ai verification (persisted runs), mandatory manual
 * per-document decisions, and payment-receipt issuance.
 *
 * <p>Status moves go through {@link InternshipLifecycleService} ONLY (S6b):
 * the first manual decision moves REPORT_SUBMITTED → UNDER_VALIDATION;
 * UNDER_VALIDATION → VALIDATED happens only when the latest decision for BOTH
 * documents is VALIDATED; a REJECTED decision (comment mandatory) returns the
 * internship to REPORT_SUBMITTED for resubmission (assumption #18). The AI
 * result itself never changes any status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternshipValidationService {

    /** Queue statuses (§5.11 step 1): everyone who sent the report onward. */
    static final Set<InternshipStatus> QUEUE_STATUSES = Set.of(
            InternshipStatus.REPORT_SUBMITTED,
            InternshipStatus.UNDER_VALIDATION,
            InternshipStatus.VALIDATED,
            InternshipStatus.RECEIPT_ISSUED);

    private static final Set<String> SORT_WHITELIST = Set.of(
            "reference", "status", "startDate", "createdAt");
    private static final int PAGE_SIZE_MAX = 100;

    private final InternshipRepository internshipRepository;
    private final ValidationRepository validationRepository;
    private final TaskRepository taskRepository;
    private final DeliverableRepository deliverableRepository;
    private final DeliverableVersionRepository deliverableVersionRepository;
    private final FileStorageService fileStorageService;
    private final DocIntelClient docIntelClient;
    private final InternshipLifecycleService lifecycleService;
    private final SupervisionScopeService supervisionScopeService;
    private final FinanceService financeService;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Queue + detail (Admin only)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ValidationQueueRow> searchQueue(ValidationQueueQuery query, UserPrincipal actor) {
        requireAdmin(actor);
        List<InternshipStatus> statuses = parseStatuses(query.statuses());
        if (statuses.isEmpty()) {
            statuses = List.copyOf(QUEUE_STATUSES);
        }
        String pattern = query.q() == null || query.q().isBlank()
                ? null : "%" + query.q().strip().toLowerCase().replace("%", "") + "%";
        Pageable pageable = PageRequest.of(Math.max(0, query.page()),
                Math.min(Math.max(1, query.size()), PAGE_SIZE_MAX),
                parseSort(query.sort()));
        return internshipRepository.searchValidationQueue(
                        statuses.isEmpty(), statuses,
                        query.supervisorUserId(), query.universityId(), query.type(),
                        pattern, pageable)
                .map(ValidationQueueRow::from);
    }

    @Transactional(readOnly = true)
    public ValidationDetailResponse getDetail(UUID internshipId, UserPrincipal actor) {
        requireAdmin(actor);
        Internship internship = findOrThrow(internshipId);
        return detailOf(internship, actor);
    }

    // ------------------------------------------------------------------
    // AI verification (advisory only — never moves a status)
    // ------------------------------------------------------------------

    @Transactional
    public VerificationResultResponse runVerification(
            UUID internshipId, ValidationDocumentType documentType, UserPrincipal actor) {
        requireAdmin(actor);
        Internship internship = findOrThrow(internshipId);
        ResolvedDocument doc = resolveDocument(internship).stream()
                .filter(d -> d.documentType() == documentType)
                .findFirst()
                .orElseThrow(() -> new ConflictException("DOCUMENT_MISSING",
                        "No submitted " + documentType + " document for this internship yet."));

        byte[] pdf = readPdf(doc);
        VerificationResultResponse response;
        if (documentType == ValidationDocumentType.REPORT) {
            response = verifyReport(internship, doc, pdf, actor);
        } else {
            response = verifyJournal(internship, doc, pdf, actor);
        }
        auditService.log("AI_VERIFICATION_RUN", "Internship", internshipId,
                null,
                Map.of("document", documentType.name(), "overall", response.overall(),
                        "degraded", response.degraded()),
                actor.getId(), null, null, null,
                tn.steg.backend.audit.domain.model.AuditSource.AI);
        return response;
    }

    // ------------------------------------------------------------------
    // Manual decisions (the ONLY path to VALIDATED)
    // ------------------------------------------------------------------

    @Transactional
    public ValidationDetailResponse recordDecision(
            UUID internshipId, ValidationDetailResponse.DecideCommand command, UserPrincipal actor) {
        requireAdmin(actor);
        if (command == null || command.documentType() == null || command.decision() == null) {
            throw new BusinessRuleException("DECISION_INVALID",
                    "A document type and a VALIDATED/REJECTED decision are required.");
        }
        if (command.decision() == ValidationDecision.REJECTED
                && (command.comment() == null || command.comment().isBlank())) {
            throw new BusinessRuleException("DECISION_COMMENT_REQUIRED",
                    "Refusing a document requires a comment for the intern.");
        }
        Internship internship = findOrThrow(internshipId);
        if (resolveDocument(internship).stream()
                .noneMatch(d -> d.documentType() == command.documentType())) {
            throw new ConflictException("DOCUMENT_MISSING",
                    "No submitted " + command.documentType() + " document for this internship yet.");
        }
        if (internship.getStatus() != InternshipStatus.REPORT_SUBMITTED
                && internship.getStatus() != InternshipStatus.UNDER_VALIDATION) {
            throw new ConflictException("VALIDATION_NOT_OPEN",
                    "Decisions are recorded while the internship is REPORT_SUBMITTED or UNDER_VALIDATION. Current: "
                            + internship.getStatus());
        }
        User decider = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));
        validationRepository.saveDecision(new DocumentValidationDecision(
                internship, command.documentType(), command.decision(),
                command.comment() == null ? null : command.comment().strip(), decider));
        auditService.log(command.decision() == ValidationDecision.VALIDATED
                        ? "VALIDATION_DECISION_VALIDATED" : "VALIDATION_DECISION_REJECTED",
                "Internship", internshipId, null,
                Map.of("document", command.documentType().name(),
                        "comment", command.comment() == null ? "" : command.comment().strip()),
                actor.getId(), null);

        // Status moves, all through the single authority:
        // 1. first decision opens the validation;
        // 2. REJECTED returns for resubmission;
        // 3. both VALIDATED completes it.
        if (internship.getStatus() == InternshipStatus.REPORT_SUBMITTED) {
            lifecycleService.transition(internshipId, InternshipStatus.UNDER_VALIDATION,
                    "Validation started (" + command.documentType() + " " + command.decision() + ")", actor);
            internship = findOrThrow(internshipId);
        }
        if (command.decision() == ValidationDecision.REJECTED) {
            if (internship.getStatus() == InternshipStatus.UNDER_VALIDATION) {
                lifecycleService.transition(internshipId, InternshipStatus.REPORT_SUBMITTED,
                        command.documentType() + " REJECTED: resubmission required", actor);
            }
            // S8 pre-check (d): the candidate learns WHAT to fix — the comment
            // travels in the notification itself, not just the audit row.
            UUID candidateUserId = internship.getCandidate() != null
                    && internship.getCandidate().getUser() != null
                    ? internship.getCandidate().getUser().getId() : null;
            if (candidateUserId != null) {
                eventPublisher.publishEvent(new DocumentRejectedEvent(
                        internshipId, internship.getReference(),
                        command.documentType().name(), command.comment().strip(),
                        candidateUserId, actor.getId()));
            }
        } else if (bothValidated(internshipId)) {
            lifecycleService.transition(internshipId, InternshipStatus.VALIDATED,
                    "Both documents manually validated", actor);
        }
        return detailOf(findOrThrow(internshipId), actor);
    }

    // ------------------------------------------------------------------
    // Receipt (delegates PDF issuance to the finance context)
    // ------------------------------------------------------------------

    @Transactional
    public tn.steg.backend.finance.application.dto.ValidationReceiptView generateReceipt(
            UUID internshipId, UserPrincipal actor) {
        requireAdmin(actor);
        Internship internship = findOrThrow(internshipId);
        var receipt = financeService.generateValidationReceipt(internshipId, actor);
        if (internship.getStatus() == InternshipStatus.VALIDATED) {
            lifecycleService.transition(internshipId, InternshipStatus.RECEIPT_ISSUED,
                    "Payment receipt " + receipt.reference() + " generated", actor);
        }
        return receipt;
    }

    // ------------------------------------------------------------------
    // Authorized document download (Admin only)
    // ------------------------------------------------------------------

    public record DownloadStream(InputStream inputStream, String fileName, String mimeType, long size) {
    }

    @Transactional
    public DownloadStream downloadDocument(
            UUID internshipId, ValidationDocumentType documentType, UserPrincipal actor, String ipAddress) {
        requireAdmin(actor);
        Internship internship = findOrThrow(internshipId);
        ResolvedDocument doc = resolveDocument(internship).stream()
                .filter(d -> d.documentType() == documentType)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No submitted " + documentType + " document for internship: " + internshipId));
        FileAsset asset = doc.asset();
        auditService.log("VALIDATION_DOCUMENT_DOWNLOADED", "Internship", internshipId,
                null, Map.of("document", documentType.name(), "version", doc.version()),
                actor.getId(), ipAddress);
        InputStream stream = fileStorageService.getInputStream(asset.getStorageKey());
        return new DownloadStream(stream, asset.getOriginalFileName(), asset.getMimeType(), asset.getSize());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Lenient status parsing: unknown values are ignored, never 400. */
    private List<InternshipStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<InternshipStatus> out = new ArrayList<>();
        for (String value : raw) {
            try {
                InternshipStatus status = InternshipStatus.valueOf(value.strip().toUpperCase());
                if (QUEUE_STATUSES.contains(status) && !out.contains(status)) {
                    out.add(status);
                }
            } catch (IllegalArgumentException ignored) {
                // unknown status token: ignored (see above)
            }
        }
        return out;
    }

    private void requireAdmin(UserPrincipal actor) {
        if (actor == null || !actor.hasRole("ADMIN")) {
            throw new AccessDeniedException("Internship validation is an Admin capability.");
        }
    }

    private Internship findOrThrow(UUID internshipId) {
        return internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
    }

    private Sort parseSort(String sort) {
        if (sort != null) {
            String[] parts = sort.split(",");
            if (parts.length == 2 && SORT_WHITELIST.contains(parts[0])) {
                Sort.Direction dir = "desc".equalsIgnoreCase(parts[1].strip())
                        ? Sort.Direction.DESC : Sort.Direction.ASC;
                if (parts[0].equals("reference") || parts[0].equals("status") || parts[0].equals("startDate")) {
                    return Sort.by(dir, parts[0]);
                }
                return Sort.by(dir, "createdAt");
            }
        }
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }

    /** Submitted deliverables, newest first. */
    private List<Deliverable> submittedDeliverables(Internship internship) {
        return deliverableRepository.findByInternshipId(internship.getId()).stream()
                .filter(d -> d.getStatus() == DeliverableStatus.SUBMITTED
                        || d.getStatus() == DeliverableStatus.VALIDATED)
                .sorted(Comparator.comparing(Deliverable::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /**
     * Document resolution (audit assumption #18): the REPORT is the oldest
     * submitted deliverable (the intern sends the report first, §4), the
     * JOURNAL is the newest submitted deliverable other than the report — so a
     * resubmitted journal after a REJECTED decision becomes the journal
     * document without ever swapping the report. A lone deliverable leaves the
     * journal pending — decisions and AI runs on it are refused until
     * submitted.
     */
    private record ResolvedDocument(ValidationDocumentType documentType, Deliverable deliverable,
            DeliverableVersion version, FileAsset asset) {
        int versionNumber() {
            return version().getVersionNumber() != null ? version().getVersionNumber() : 1;
        }
    }

    private List<ResolvedDocument> resolveDocument(Internship internship) {
        List<Deliverable> submitted = submittedDeliverables(internship);
        if (submitted.isEmpty()) {
            return List.of();
        }
        List<ResolvedDocument> out = new ArrayList<>();
        // Oldest submitted first: index 0 is the report.
        Deliverable report = submitted.get(submitted.size() - 1);
        resolveOne(ValidationDocumentType.REPORT, report).ifPresent(out::add);
        // Newest submitted other than the report is the journal.
        submitted.stream()
                .filter(d -> !d.getId().equals(report.getId()))
                .findFirst()
                .flatMap(journal -> resolveOne(ValidationDocumentType.JOURNAL, journal))
                .ifPresent(out::add);
        return List.copyOf(out);
    }

    private Optional<ResolvedDocument> resolveOne(ValidationDocumentType type, Deliverable deliverable) {
        List<DeliverableVersion> versions =
                deliverableVersionRepository.findByDeliverableIdOrderByVersionNumberAsc(deliverable.getId());
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        DeliverableVersion current = versions.get(versions.size() - 1);
        if (current.getFile() == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedDocument(type, deliverable, current, current.getFile()));
    }

    private ValidationDetailResponse detailOf(Internship internship, UserPrincipal actor) {
        Candidate candidate = internship.getCandidate();
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).strip() : "—";
        var supervisorUser = internship.getSupervisorUser();
        String supervisorDisplay = null;
        if (supervisorUser != null) {
            supervisorDisplay = employeeRepository.findByUserId(supervisorUser.getId())
                    .map(e -> (e.getFirstName() + " " + e.getLastName()).strip())
                    .filter(s -> !s.isBlank())
                    .orElse(supervisorUser.getEmail());
        }
        // T04/D8b: not-yet-visible scheduled tasks are excluded from the
        // denominator AND the item list (same shared rule as the student
        // board) — total always equals the visible items.
        List<Task> tasks = TaskCompletionPolicy.withoutHidden(
                taskRepository.findByInternshipId(internship.getId()), java.time.Instant.now());
        TaskCompletionPolicy.CompletionRatio ratio = TaskCompletionPolicy.ratio(tasks);
        List<ResolvedDocument> docs = resolveDocument(internship);
        Optional<ResolvedDocument> report = docs.stream()
                .filter(d -> d.documentType() == ValidationDocumentType.REPORT).findFirst();
        Optional<ResolvedDocument> journal = docs.stream()
                .filter(d -> d.documentType() == ValidationDocumentType.JOURNAL).findFirst();
        return new ValidationDetailResponse(
                internship.getId(), internship.getReference(), internship.getStatus().name(),
                internship.getType() != null ? internship.getType().name() : null,
                internship.getRequirement() != null ? internship.getRequirement().name() : null,
                internship.getStartDate() != null ? internship.getStartDate().toString() : null,
                internship.getEndDate() != null ? internship.getEndDate().toString() : null,
                new ValidationDetailResponse.CandidateInfo(
                        candidate != null ? candidate.getId() : null, candidateName,
                        candidate != null ? candidate.getEmail() : null,
                        candidate != null && candidate.getUniversity() != null
                                ? candidate.getUniversity().getName() : null),
                new ValidationDetailResponse.SupervisorInfo(
                        supervisorUser != null ? supervisorUser.getId() : null,
                        supervisorUser != null ? supervisorUser.getEmail() : null,
                        supervisorDisplay),
                new ValidationDetailResponse.TaskSummary(ratio.total(), ratio.done(),
                        tasks.stream().map(t -> new ValidationDetailResponse.TaskItem(
                                t.getId(), t.getTitle(),
                                t.getStatus() != null ? t.getStatus().name() : null)).toList()),
                report.map(this::toDocRef).orElse(null),
                journal.map(this::toDocRef).orElse(null),
                report.map(d -> toRunView(internship.getId(), d.documentType())).orElse(null),
                journal.map(d -> toRunView(internship.getId(), d.documentType())).orElse(null),
                report.map(d -> toDecisionView(internship.getId(), d.documentType())).orElse(null),
                journal.map(d -> toDecisionView(internship.getId(), d.documentType())).orElse(null),
                financeService.findValidationReceipt(internship.getId())
                        .map(r -> new ValidationDetailResponse.ReceiptInfo(
                                r.reference(),
                                r.amount() != null ? r.amount().toPlainString() : null,
                                r.currency(), true))
                        .orElse(null));
    }

    private ValidationDetailResponse.DocumentRef toDocRef(ResolvedDocument doc) {
        FileAsset asset = doc.asset();
        return new ValidationDetailResponse.DocumentRef(
                doc.documentType().name(),
                doc.deliverable().getId(),
                asset.getId(),
                doc.versionNumber(),
                doc.deliverable().getTitle(),
                doc.deliverable().getSubmittedAt(),
                asset.getSize());
    }

    private ValidationDetailResponse.RunView toRunView(UUID internshipId, ValidationDocumentType type) {
        return validationRepository
                .findRunsByInternshipIdAndDocumentTypeOrderByRunAtDesc(internshipId, type).stream()
                .findFirst()
                .map(run -> {
                    List<ValidationDetailResponse.CheckView> checks = readChecks(run.getResultJson());
                    User by = run.getRunBy();
                    return new ValidationDetailResponse.RunView(run.getOverall(), run.isDegraded(), checks,
                            by != null ? by.getId() : null, run.getRunAt());
                })
                .orElse(null);
    }

    private ValidationDetailResponse.DecisionView toDecisionView(UUID internshipId, ValidationDocumentType type) {
        return validationRepository.latestDecision(internshipId, type)
                .map(d -> new ValidationDetailResponse.DecisionView(
                        d.getDecision() != null ? d.getDecision().name() : null,
                        d.getComment(),
                        d.getDecidedBy() != null ? d.getDecidedBy().getId() : null,
                        d.getDecidedAt()))
                .orElse(null);
    }

    private List<ValidationDetailResponse.CheckView> readChecks(String resultJson) {
        try {
            var root = objectMapper.readTree(resultJson);
            var checks = root.path("checks");
            List<ValidationDetailResponse.CheckView> out = new ArrayList<>();
            if (checks.isArray()) {
                for (var c : checks) {
                    out.add(new ValidationDetailResponse.CheckView(
                            c.path("key").asText(""), c.path("label").asText(""),
                            c.path("status").asText(""), c.path("expected").asText(""),
                            c.path("found").asText(""), c.path("evidence").asText("")));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean bothValidated(UUID internshipId) {
        for (ValidationDocumentType type : ValidationDocumentType.values()) {
            var latest = validationRepository.latestDecision(internshipId, type);
            if (latest.isEmpty()
                    || latest.get().getDecision() != tn.steg.backend.internship.domain.model.ValidationDecision.VALIDATED) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // AI verification workers
    // ------------------------------------------------------------------

    private byte[] readPdf(ResolvedDocument doc) {
        try (InputStream in = fileStorageService.getInputStream(doc.asset().getStorageKey())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("Validation PDF unreadable for deliverable {}: {}", doc.deliverable().getId(), e.toString());
            return null;
        }
    }

    private VerificationResultResponse verifyReport(
            Internship internship, ResolvedDocument doc, byte[] pdf, UserPrincipal actor) {
        if (pdf == null) {
            return persistDegraded(internship, doc, actor, "stored report file is unreadable");
        }
        Candidate candidate = internship.getCandidate();
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).strip() : "";
        var expected = new DocIntelClient.ReportExpected(
                candidateName,
                supervisorDisplayName(internship),
                internship.getType() != null ? internship.getType().name() : "",
                internship.getStartDate() != null ? internship.getStartDate().toString() : "",
                internship.getEndDate() != null ? internship.getEndDate().toString() : "",
                candidate != null && candidate.getUniversity() != null
                        ? candidate.getUniversity().getName() : "");
        Optional<DocIntelClient.VerificationResult> result;
        try {
            result = docIntelClient.verifyReport(pdf, expected);
        } catch (BusinessRuleException bre) {
            // Deterministic python-ai refusal (bad token → AI_UNAVAILABLE,
            // oversized/unparsable PDF): degraded, never blocking.
            return persistDegraded(internship, doc, actor, bre.getMessage());
        } catch (Exception e) {
            return persistDegraded(internship, doc, actor, e.toString());
        }
        if (result.isEmpty()) {
            return persistDegraded(internship, doc, actor, "document intelligence service unavailable");
        }
        return persistRun(internship, doc, actor, result.get(), false);
    }

    private VerificationResultResponse verifyJournal(
            Internship internship, ResolvedDocument doc, byte[] pdf, UserPrincipal actor) {
        if (pdf == null) {
            return persistDegraded(internship, doc, actor, "stored journal file is unreadable");
        }
        // T04/D8b: see detailOf — hidden tasks never count toward the 75 %.
        List<Task> tasks = TaskCompletionPolicy.withoutHidden(
                taskRepository.findByInternshipId(internship.getId()), java.time.Instant.now());
        TaskCompletionPolicy.CompletionRatio ratio = TaskCompletionPolicy.ratio(tasks);
        var expected = new DocIntelClient.JournalExpected(
                internship.getStartDate() != null ? internship.getStartDate().toString() : "",
                internship.getEndDate() != null ? internship.getEndDate().toString() : "",
                ratio.done(), ratio.total());
        Optional<DocIntelClient.VerificationResult> result;
        try {
            result = docIntelClient.verifyJournal(pdf, expected);
        } catch (BusinessRuleException bre) {
            return persistDegraded(internship, doc, actor, bre.getMessage());
        } catch (Exception e) {
            return persistDegraded(internship, doc, actor, e.toString());
        }
        if (result.isEmpty()) {
            return persistDegraded(internship, doc, actor, "document intelligence service unavailable");
        }
        return persistRun(internship, doc, actor, result.get(), false);
    }

    private String supervisorDisplayName(Internship internship) {
        var supervisorUser = internship.getSupervisorUser();
        if (supervisorUser == null) {
            return "";
        }
        return employeeRepository.findByUserId(supervisorUser.getId())
                .map(e -> (e.getFirstName() + " " + e.getLastName()).strip())
                .filter(s -> !s.isBlank())
                .orElse(supervisorUser.getEmail() != null ? supervisorUser.getEmail() : "");
    }

    private VerificationResultResponse persistRun(Internship internship, ResolvedDocument doc,
                                                  UserPrincipal actor, DocIntelClient.VerificationResult result,
                                                  boolean degraded) {
        String json;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("overall", result.overall());
            payload.put("checks", result.checks().stream().map(c -> Map.of(
                    "key", c.key(), "label", c.label(), "status", c.status(),
                    "expected", c.expected(), "found", c.found(), "evidence", c.evidence())).toList());
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            json = "{\"overall\":\"" + result.overall() + "\",\"checks\":[]}";
        }
        User runBy = userRepository.findById(actor.getId()).orElse(null);
        VerificationRun run = validationRepository.saveRun(new VerificationRun(
                internship, doc.documentType(), doc.deliverable().getId(), doc.asset().getId(),
                doc.versionNumber(), runBy, result.overall(), degraded, json));
        return new VerificationResultResponse(doc.documentType().name(), result.overall(), degraded,
                result.checks().stream().map(c -> new ValidationDetailResponse.CheckView(
                        c.key(), c.label(), c.status(), c.expected(), c.found(), c.evidence())).toList(),
                run.getId().toString());
    }

    private VerificationResultResponse persistDegraded(
            Internship internship, ResolvedDocument doc, UserPrincipal actor, String reason) {
        String safe = reason != null ? reason : "unavailable";
        var empty = new DocIntelClient.VerificationResult("INCONCLUSIVE", List.of(
                new DocIntelClient.VerificationCheck("service", "Document intelligence service",
                        "INCONCLUSIVE", "available", "unavailable", safe)));
        return persistRun(internship, doc, actor, empty, true);
    }
}
