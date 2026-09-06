package tn.steg.backend.finance.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.event.PaymentApprovedEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.DocumentVerificationStatus;
import tn.steg.backend.document.domain.model.DocumentVersion;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;
import tn.steg.backend.document.domain.repository.DocumentRepository;
import tn.steg.backend.document.domain.repository.DocumentVersionRepository;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.repository.FinanceCaseDocumentRepository;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.OfficialBrandingProvider;
import tn.steg.backend.document.domain.service.PdfDocumentRenderer;
import tn.steg.backend.document.domain.service.PdfTemplateProvider;
import tn.steg.backend.finance.application.dto.AttachFinanceDocumentRequest;
import tn.steg.backend.finance.application.dto.FinanceCaseDocumentResponse;
import tn.steg.backend.finance.application.dto.FinanceCaseResponse;
import tn.steg.backend.finance.application.dto.PaymentApprovalResponse;
import tn.steg.backend.finance.application.dto.PaymentCalculationResponse;
import tn.steg.backend.finance.application.dto.PaymentDecisionRequest;
import tn.steg.backend.finance.application.dto.ReviewFinanceDocumentRequest;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.finance.domain.model.PaymentApproval;
import tn.steg.backend.finance.domain.model.PaymentApprovalDecision;
import tn.steg.backend.finance.domain.model.PaymentCalculation;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.finance.domain.model.PaymentReceiptStatus;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentApprovalRepository;
import tn.steg.backend.finance.domain.repository.PaymentCalculationRepository;
import tn.steg.backend.finance.domain.repository.PaymentReceiptRepository;
import tn.steg.backend.finance.domain.service.FinanceDossierPolicy;
import tn.steg.backend.finance.domain.service.PaymentCalculationResult;
import tn.steg.backend.finance.domain.service.PaymentCalculationService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.internship.domain.service.InternshipEligibilityService;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service for the finance pipeline (Phase A11): FinanceCase
 * lifecycle, deterministic payment calculation snapshots, human FINANCE
 * approvals, and payment receipt issuance.
 *
 * <p>Invariants (all enforced server-side, never client-supplied):
 * <ul>
 *   <li>Cases open only for OBLIGATOIRE + COMPLETED internships, once per internship.</li>
 *   <li>Amounts always recomputed by {@link PaymentCalculationService}; the API
 *       never accepts an amount, month count, or rate from the client.</li>
 *   <li>A decided case (APPROVED/REJECTED/CLOSED) is immutable: recalculation,
 *       dossier edits and further decisions are rejected. Recalculation appends
 *       a new versioned snapshot row (never an in-place update), so every
 *       decided amount stays traceable — in-table history plus audit old/new JSON.</li>
 *   <li>Only an Employee holding the FINANCE role may approve/reject.</li>
 *   <li>Every status change routes through the payment workflow engine.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FinanceCaseRepository financeCaseRepository;
    private final PaymentCalculationRepository calculationRepository;
    private final PaymentApprovalRepository approvalRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final FinanceCaseDocumentRepository financeCaseDocumentRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final FileAssetRepository fileAssetRepository;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;
    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final PdfTemplateProvider templateProvider;
    private final OfficialBrandingProvider brandingProvider;
    private final PdfDocumentRenderer pdfRenderer;

    private final InternshipEligibilityService eligibilityService = new InternshipEligibilityService();

    @Value("${steg.finance.rate-per-month:50.00}")
    private BigDecimal ratePerMonth;

    @Value("${steg.finance.max-payable-months:3}")
    private int maxPayableMonths;

    @Value("${steg.finance.max-amount:150.00}")
    private BigDecimal maxAmount;

    @Value("${steg.finance.currency:TND}")
    private String currency;

    @Value("${steg.documents.receipt.template-code:STEG-PAYMENT-RECEIPT}")
    private String receiptTemplateCode;

    @Value("${steg.documents.receipt.template-version:1}")
    private int receiptTemplateVersion;

    public record DownloadStream(InputStream inputStream, String fileName, String mimeType, long size) {
    }

    // ------------------------------------------------------------------
    // Case lifecycle
    // ------------------------------------------------------------------

    @Transactional
    public FinanceCaseResponse openFinanceCase(UUID internshipId, UserPrincipal actor) {
        requireFinanceStaff(actor);
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
        if (!eligibilityService.isPaymentEligible(internship)) {
            throw new BusinessRuleException("INTERNSHIP_NOT_PAYABLE",
                    "Only OBLIGATOIRE internships are eligible for the payment workflow. Requirement: "
                            + internship.getRequirement());
        }
        if (internship.getStatus() != InternshipStatus.COMPLETED) {
            throw new BusinessRuleException("INTERNSHIP_NOT_COMPLETED",
                    "A finance case can only be opened for a COMPLETED internship. Current: " + internship.getStatus());
        }
        if (financeCaseRepository.findByInternshipId(internshipId).isPresent()) {
            throw new BusinessRuleException("FINANCE_CASE_ALREADY_EXISTS",
                    "A finance case already exists for internship: " + internship.getReference());
        }

        // References are count-based with an exists-check (collisions across
        // concurrent opens are near-impossible at human pace; any residual
        // unique violation surfaces as a safe 409 via the global handler).
        FinanceCase financeCase = financeCaseRepository.save(new FinanceCase(nextCaseReference(), internship));
        storeCalculationSnapshot(financeCase, internship);
        workflowService.spawnPaymentWorkflow(financeCase);

        auditService.log("FINANCE_CASE_OPENED", "FinanceCase", financeCase.getId(),
                null, Map.of("reference", financeCase.getReference()), actor.getId(), null);
        log.info("Finance case opened: ref={} internship={} actor={}",
                financeCase.getReference(), internship.getReference(), actor.getId());
        return toResponse(financeCase);
    }

    @Transactional(readOnly = true)
    public Page<FinanceCaseResponse> listFinanceCases(FinanceCaseStatus status, Pageable pageable) {
        // A14 N+1 fix: internship fetched in the page query itself (see port).
        Page<FinanceCase> page = status == null
                ? financeCaseRepository.findAllWithInternship(pageable)
                : financeCaseRepository.findByStatusWithInternship(status, pageable);
        if (page.isEmpty()) {
            return page.map(this::toResponse);
        }
        // A14 N+1 fix: preload every child collection for the whole page in
        // five bulk queries (plus one batched internship load via @BatchSize)
        // instead of ~6 queries per finance case. Identical output to the
        // per-case assembly below.
        List<UUID> caseIds = page.getContent().stream().map(FinanceCase::getId).toList();
        Map<UUID, List<PaymentCalculation>> calculationsByCase = calculationRepository
                .findAllByFinanceCaseIdInOrderByCalculationSequenceAsc(caseIds).stream()
                .collect(Collectors.groupingBy(c -> c.getFinanceCase().getId()));
        Map<UUID, List<FinanceCaseDocument>> documentsByCase = financeCaseDocumentRepository
                .findByFinanceCaseIdInWithDetails(caseIds).stream()
                .collect(Collectors.groupingBy(d -> d.getFinanceCase().getId()));
        Map<UUID, List<PaymentApproval>> approvalsByCase = approvalRepository
                .findByFinanceCaseIdInWithDecider(caseIds).stream()
                .collect(Collectors.groupingBy(a -> a.getFinanceCase().getId()));
        Map<UUID, String> receiptReferenceByCase = new HashMap<>();
        for (PaymentReceipt receipt : receiptRepository.findByFinanceCaseIdIn(caseIds)) {
            receiptReferenceByCase.put(receipt.getFinanceCase().getId(), receipt.getReference());
        }
        Map<UUID, UUID> workflowInstanceByCase = workflowService.paymentWorkflowInstanceIdsFor(caseIds);

        return page.map(financeCase -> {
            UUID caseId = financeCase.getId();
            List<PaymentCalculation> history =
                    calculationsByCase.getOrDefault(caseId, List.of());
            PaymentCalculationResponse calculation = history.isEmpty() ? null
                    : PaymentCalculationResponse.from(history.get(history.size() - 1));
            List<FinanceCaseDocumentResponse> documents = documentsByCase
                    .getOrDefault(caseId, List.of()).stream()
                    .map(FinanceCaseDocumentResponse::from).toList();
            List<PaymentApprovalResponse> approvals = approvalsByCase
                    .getOrDefault(caseId, List.of()).stream()
                    .map(PaymentApprovalResponse::from).toList();
            return FinanceCaseResponse.from(financeCase, calculation, documents, approvals,
                    receiptReferenceByCase.get(caseId), workflowInstanceByCase.get(caseId));
        });
    }

    @Transactional(readOnly = true)
    public FinanceCaseResponse getFinanceCase(UUID financeCaseId) {
        return toResponse(findCaseOrThrow(financeCaseId));
    }

    // ------------------------------------------------------------------
    // Dossier
    // ------------------------------------------------------------------

    @Transactional
    public FinanceCaseDocumentResponse attachDocument(UUID financeCaseId, AttachFinanceDocumentRequest request,
                                                     UserPrincipal actor) {
        requireFinanceStaff(actor);
        FinanceCase financeCase = findMutableCaseOrThrow(financeCaseId);
        Document document = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + request.documentId()));

        FinanceCaseDocument link = financeCaseDocumentRepository
                .findByFinanceCaseIdAndDocumentId(financeCaseId, document.getId())
                .orElseGet(() -> new FinanceCaseDocument(financeCase, document,
                        request.mandatory() != null ? request.mandatory() : true));
        link.setMandatory(request.mandatory() != null ? request.mandatory() : link.getMandatory());
        link = financeCaseDocumentRepository.save(link);

        auditService.log("FINANCE_DOCUMENT_ATTACHED", "FinanceCase", financeCaseId,
                null, Map.of("document", document.getReference(), "type", document.getType().name()),
                actor.getId(), null);
        refreshDossierStatus(financeCase, actor);
        return FinanceCaseDocumentResponse.from(link);
    }

    @Transactional
    public FinanceCaseDocumentResponse reviewDocument(UUID financeCaseId, UUID documentId,
                                                     ReviewFinanceDocumentRequest request, UserPrincipal actor) {
        requireFinanceStaff(actor);
        FinanceCase financeCase = findMutableCaseOrThrow(financeCaseId);
        FinanceCaseDocument link = financeCaseDocumentRepository
                .findByFinanceCaseIdAndDocumentId(financeCaseId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document is not attached to finance case: " + documentId));

        Employee reviewer = employeeRepository.findByUserId(actor.getId()).orElse(null);
        link.setVerificationStatus(request.status());
        link.setVerificationComment(request.comment());
        link.setReviewedBy(reviewer);
        link.setReviewedAt(Instant.now());
        link = financeCaseDocumentRepository.save(link);

        auditService.log("FINANCE_DOCUMENT_REVIEWED", "FinanceCaseDocument", link.getId(),
                null, Map.of("status", request.status().name()), actor.getId(), null);
        refreshDossierStatus(financeCase, actor);
        return FinanceCaseDocumentResponse.from(link);
    }

    // ------------------------------------------------------------------
    // Recalculation (pre-decision only)
    // ------------------------------------------------------------------

    @Transactional
    public FinanceCaseResponse recalculate(UUID financeCaseId, UserPrincipal actor) {
        requireFinanceStaff(actor);
        FinanceCase financeCase = findMutableCaseOrThrow(financeCaseId);

        PaymentCalculationResult result = calculateFor(financeCase.getInternship());
        List<PaymentCalculation> history =
                calculationRepository.findAllByFinanceCaseIdOrderByCalculationSequenceAsc(financeCaseId);
        PaymentCalculation old = history.isEmpty() ? null : history.get(history.size() - 1);
        int nextSequence = old == null ? 1 : old.getCalculationSequence() + 1;
        Map<String, Object> oldSnapshot = old == null ? null : Map.of(
                "sequence", old.getCalculationSequence(),
                "completedMonths", old.getCompletedMonths(),
                "payableMonths", old.getPayableMonths(),
                "calculatedAmount", old.getCalculatedAmount(),
                "cappedAmount", old.getCappedAmount());

        // Immutable snapshots: append a new versioned row, never update or
        // delete the previous one (V22 unique case+sequence).
        PaymentCalculation fresh = calculationRepository.save(new PaymentCalculation(
                financeCase, result.completedMonths(), result.payableMonths(),
                result.ratePerMonth(), result.calculatedAmount(), result.cappedAmount(),
                result.capApplied(), result.currencyCode(), nextSequence));

        auditService.log("PAYMENT_RECALCULATED", "FinanceCase", financeCaseId,
                oldSnapshot,
                Map.of("sequence", fresh.getCalculationSequence(),
                        "completedMonths", fresh.getCompletedMonths(),
                        "payableMonths", fresh.getPayableMonths(),
                        "calculatedAmount", fresh.getCalculatedAmount(),
                        "cappedAmount", fresh.getCappedAmount()),
                actor.getId(), null);
        log.info("Payment recalculated: case={} cappedAmount={}", financeCase.getReference(), fresh.getCappedAmount());
        return toResponse(financeCase);
    }

    // ------------------------------------------------------------------
    // Decisions (FINANCE role only — the human authority)
    // ------------------------------------------------------------------

    @Transactional
    public FinanceCaseResponse approve(UUID financeCaseId, PaymentDecisionRequest request, UserPrincipal actor) {
        Employee decider = requireFinanceEmployee(actor);
        FinanceCase financeCase = findMutableCaseOrThrow(financeCaseId);
        if (financeCase.getStatus() != FinanceCaseStatus.READY_FOR_DECISION) {
            throw new BusinessRuleException("FINANCE_CASE_NOT_READY",
                    "Payment can only be approved once the dossier is READY_FOR_DECISION. Current: "
                            + financeCase.getStatus());
        }

        // Workflow first (guard validates against the pre-decision state),
        // then the terminal mutation — one atomic transaction.
        workflowService.transitionPayment(financeCaseId,
                new WorkflowTransitionRequest("PAYMENT_APPROVED", WorkflowActionType.APPROVAL,
                        ApprovalDecision.APPROVED, request != null ? request.comment() : null),
                actor);

        List<PaymentCalculation> history =
                calculationRepository.findAllByFinanceCaseIdOrderByCalculationSequenceAsc(financeCaseId);
        PaymentCalculation calculation = history.isEmpty() ? null : history.get(history.size() - 1);
        if (calculation == null) {
            throw new BusinessRuleException("CALCULATION_MISSING",
                    "Cannot approve a finance case without a payment calculation snapshot.");
        }

        int sequence = (int) approvalRepository.countByFinanceCaseId(financeCaseId) + 1;
        approvalRepository.save(new PaymentApproval(financeCase, decider,
                PaymentApprovalDecision.APPROVED, request != null ? request.comment() : null, sequence));

        PaymentReceipt receipt = issueReceipt(financeCase, calculation, decider, actor);

        financeCase.setStatus(FinanceCaseStatus.APPROVED);
        financeCase.setClosedAt(Instant.now());
        financeCaseRepository.save(financeCase);

        auditService.log("FINANCE_CASE_APPROVED", "FinanceCase", financeCaseId,
                Map.of("status", "READY_FOR_DECISION"),
                Map.of("status", "APPROVED", "receipt", receipt.getReference()),
                actor.getId(), null);
        log.info("Finance case approved: ref={} amount={} actor={}",
                financeCase.getReference(), calculation.getCappedAmount(), actor.getId());

        assignmentRepository.findByInternshipIdAndStatus(
                        financeCase.getInternship().getId(), AssignmentStatus.ACTIVE)
                .map(a -> a.getSupervisor() != null && a.getSupervisor().getUser() != null
                        ? a.getSupervisor().getUser().getId() : null)
                .ifPresentOrElse(
                        supervisorUserId -> eventPublisher.publishEvent(new PaymentApprovedEvent(
                                financeCaseId, financeCase.getReference(), supervisorUserId,
                                calculation.getCappedAmount(), calculation.getPayableMonths(), actor.getId())),
                        () -> log.warn("No active supervisor to notify for approved case {}", financeCase.getReference()));

        return toResponse(financeCase);
    }

    @Transactional
    public FinanceCaseResponse reject(UUID financeCaseId, PaymentDecisionRequest request, UserPrincipal actor) {
        Employee decider = requireFinanceEmployee(actor);
        FinanceCase financeCase = findMutableCaseOrThrow(financeCaseId);
        if (request == null || request.comment() == null || request.comment().isBlank()) {
            throw new BusinessRuleException("REJECTION_REASON_REQUIRED",
                    "Rejecting a finance case requires a comment explaining the reason.");
        }

        workflowService.transitionPayment(financeCaseId,
                new WorkflowTransitionRequest("PAYMENT_APPROVED", WorkflowActionType.APPROVAL,
                        ApprovalDecision.REJECTED, request.comment()),
                actor);

        int sequence = (int) approvalRepository.countByFinanceCaseId(financeCaseId) + 1;
        approvalRepository.save(new PaymentApproval(financeCase, decider,
                PaymentApprovalDecision.REJECTED, request.comment(), sequence));

        financeCase.setStatus(FinanceCaseStatus.REJECTED);
        financeCase.setClosedAt(Instant.now());
        financeCaseRepository.save(financeCase);

        auditService.log("FINANCE_CASE_REJECTED", "FinanceCase", financeCaseId,
                Map.of("status", "READY_FOR_DECISION"),
                Map.of("status", "REJECTED", "reason", request.comment()),
                actor.getId(), null);
        log.info("Finance case rejected: ref={} actor={}", financeCase.getReference(), actor.getId());
        return toResponse(financeCase);
    }

    // ------------------------------------------------------------------
    // Receipt download
    // ------------------------------------------------------------------

    @Transactional
    public DownloadStream downloadReceipt(UUID financeCaseId, UserPrincipal actor, String ipAddress) {
        FinanceCase financeCase = findCaseOrThrow(financeCaseId);
        PaymentReceipt receipt = receiptRepository.findByFinanceCaseId(financeCaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment receipt available for finance case: " + financeCase.getReference()));
        assertCanDownloadReceipt(financeCase, actor);

        auditService.log("PAYMENT_RECEIPT_DOWNLOADED", "PaymentReceipt", receipt.getId(),
                null, null, actor.getId(), ipAddress);

        FileAsset fileAsset = receipt.getPdfFile();
        InputStream stream = fileStorageService.getInputStream(fileAsset.getStorageKey());
        return new DownloadStream(stream, fileAsset.getOriginalFileName(), fileAsset.getMimeType(), fileAsset.getSize());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private FinanceCase findCaseOrThrow(UUID financeCaseId) {
        return financeCaseRepository.findById(financeCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Finance case not found: " + financeCaseId));
    }

    /**
     * Loads the case under a pessimistic write lock. All mutating paths
     * (attach, review, recalculate, approve, reject) go through here so
     * concurrent attempts serialize per case: exactly one decision wins and
     * losers observe the terminal state (clean 422) instead of racing.
     */
    private FinanceCase findMutableCaseOrThrow(UUID financeCaseId) {
        FinanceCase financeCase = financeCaseRepository.findByIdForUpdate(financeCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Finance case not found: " + financeCaseId));
        if (financeCase.getStatus() == FinanceCaseStatus.APPROVED
                || financeCase.getStatus() == FinanceCaseStatus.REJECTED
                || financeCase.getStatus() == FinanceCaseStatus.CLOSED) {
            throw new BusinessRuleException("FINANCE_CASE_DECIDED",
                    "A decided finance case is immutable. Current: " + financeCase.getStatus());
        }
        return financeCase;
    }

    private void requireFinanceStaff(UserPrincipal actor) {
        if (!actor.hasRole("FINANCE") && !actor.hasRole("ADMIN")) {
            throw new AccessDeniedException("Finance staff role (FINANCE or ADMIN) is required.");
        }
    }

    /**
     * The human payment authority: an Employee holding the FINANCE role.
     * ADMIN alone is deliberately insufficient — the spec reserves approval
     * for Finance, and AI (Phase A12) has no path here at all.
     */
    private Employee requireFinanceEmployee(UserPrincipal actor) {
        if (!actor.hasRole("FINANCE")) {
            throw new AccessDeniedException("Payment approval requires the FINANCE role.");
        }
        return employeeRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new AccessDeniedException("A linked finance employee profile is required."));
    }

    private void assertCanDownloadReceipt(FinanceCase financeCase, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("FINANCE") || actor.hasRole("HR")) {
            return;
        }
        boolean supervisor = assignmentRepository.findByInternshipIdAndStatus(
                        financeCase.getInternship().getId(), AssignmentStatus.ACTIVE)
                .map(a -> a.getSupervisor() != null && a.getSupervisor().getUser() != null
                        && a.getSupervisor().getUser().getId().equals(actor.getId()))
                .orElse(false);
        if (!supervisor) {
            throw new AccessDeniedException("You are not authorized to download this payment receipt.");
        }
    }

    private PaymentCalculationResult calculateFor(Internship internship) {
        return PaymentCalculationService.calculate(
                internship.getStartDate(), internship.getEndDate(),
                ratePerMonth, maxPayableMonths, maxAmount, currency);
    }

    private void storeCalculationSnapshot(FinanceCase financeCase, Internship internship) {
        PaymentCalculationResult result = calculateFor(internship);
        calculationRepository.save(new PaymentCalculation(
                financeCase, result.completedMonths(), result.payableMonths(),
                result.ratePerMonth(), result.calculatedAmount(), result.cappedAmount(),
                result.capApplied(), result.currencyCode(), 1));
    }

    /**
     * Recomputes dossier completeness after every attach/review and advances
     * the case (plus its workflow instance) out of OPENED exactly once.
     */
    private void refreshDossierStatus(FinanceCase financeCase, UserPrincipal actor) {
        FinanceCaseStatus old = financeCase.getStatus();
        boolean complete = isDossierComplete(financeCase.getId());
        FinanceCaseStatus next;
        if (complete) {
            next = FinanceCaseStatus.READY_FOR_DECISION;
        } else if (old == FinanceCaseStatus.OPENED) {
            next = FinanceCaseStatus.UNDER_REVIEW;
        } else if (old == FinanceCaseStatus.READY_FOR_DECISION) {
            next = FinanceCaseStatus.DOCUMENTS_MISSING;
        } else {
            next = FinanceCaseStatus.DOCUMENTS_MISSING;
        }
        if (next == old) {
            return;
        }
        if (old == FinanceCaseStatus.OPENED) {
            workflowService.transitionPayment(financeCase.getId(),
                    new WorkflowTransitionRequest("FINANCE_VERIFICATION", WorkflowActionType.VALIDATION, null,
                            "Dossier review started"),
                    actor);
        }
        financeCase.setStatus(next);
        financeCaseRepository.save(financeCase);
        auditService.log("FINANCE_CASE_STATUS_CHANGED", "FinanceCase", financeCase.getId(),
                Map.of("status", old.name()), Map.of("status", next.name()), actor.getId(), null);
        log.info("Finance case {} status: {} → {}", financeCase.getReference(), old, next);
    }

    private boolean isDossierComplete(UUID financeCaseId) {
        List<FinanceCaseDocument> links = financeCaseDocumentRepository.findByFinanceCaseId(financeCaseId);
        return FinanceDossierPolicy.requiredTypes().stream().allMatch(required ->
                links.stream().anyMatch(link ->
                        link.getDocument().getType() == required
                                && link.getVerificationStatus()
                                == tn.steg.backend.document.domain.model.DocumentVerificationStatus.VERIFIED));
    }

    private PaymentReceipt issueReceipt(FinanceCase financeCase, PaymentCalculation calculation,
                                        Employee issuer, UserPrincipal actor) {
        Internship internship = financeCase.getInternship();
        // Allocated once per approval; the case row lock held by approve() rules
        // out same-case races, and any residual cross-case collision surfaces as
        // a safe 409 (count + exists-check first). Rendered content always
        // matches the persisted reference because both come from this variable.
        String reference = nextReceiptReference();
        LocalDate today = LocalDate.now();
        // Amounts below come exclusively from the approved immutable snapshot
        // (never a client value, never a post-approval recalculation).
        // NOTE: nationalId is sensitive (CIN). It is used here because the
        // official template requires it, never logged, and the resulting PDF
        // inherits restricted download + access auditing.
        Map<String, String> values = Map.ofEntries(
                Map.entry("internFullName", fullName(internship)),
                Map.entry("nationalId", nationalId(internship)),
                Map.entry("university", university(internship)),
                Map.entry("internshipType", prettyType(internship)),
                Map.entry("internshipStartDate", internship.getStartDate().format(DATE_FORMAT)),
                Map.entry("internshipEndDate", internship.getEndDate().format(DATE_FORMAT)),
                Map.entry("internshipPeriod", "du " + internship.getStartDate().format(DATE_FORMAT)
                        + " au " + internship.getEndDate().format(DATE_FORMAT)),
                Map.entry("payableMonths", String.valueOf(calculation.getPayableMonths())),
                Map.entry("approvedAmount", calculation.getCappedAmount().toPlainString()),
                Map.entry("currency", calculation.getCurrencyCode()),
                Map.entry("financeReference", financeCase.getReference()),
                Map.entry("paymentReference", reference),
                Map.entry("receiptReference", reference),
                Map.entry("decisionDate", today.format(DATE_FORMAT)),
                Map.entry("generationDate", today.format(DATE_FORMAT)));

        PdfTemplateProvider.StructuredTemplate template =
                templateProvider.resolveStructured("payment-receipt-template", values);
        byte[] pdf = pdfRenderer.renderStructured(template.title(), template.subtitle(),
                toBlocks(template), template.footer(), brandingProvider.getLogoPngBytes());

        User actorUser = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));
        // Filename carries the stable case reference (not the receipt reference,
        // which is allocated afterwards): no file rewrite is ever needed on a
        // reference retry, so retries cannot orphan stored files.
        FileAsset fileAsset = storeGeneratedPdf(pdf, "recu-paiement-" + financeCase.getReference() + ".pdf", actorUser);
        createLinkedDocument(fileAsset, DocumentType.PAYMENT_RECEIPT);

        // The case row lock held by approve() serializes decisions per case, so
        // this insert cannot race a sibling approval; cross-case reference
        // races fall back to a safe 409 (count + exists-check first).
        PaymentReceipt receipt = new PaymentReceipt(nextReceiptReference(), financeCase, fileAsset,
                calculation.getCappedAmount(), calculation.getPayableMonths(), calculation.getCurrencyCode());
        receipt.setIssuedBy(issuer);
        receipt.setStatus(PaymentReceiptStatus.ISSUED);
        receipt.setIssuedAt(Instant.now());
        receipt.setPaymentDate(today);
        receipt = receiptRepository.save(receipt);

        auditService.log("PAYMENT_RECEIPT_ISSUED", "PaymentReceipt", receipt.getId(),
                null, Map.of("reference", receipt.getReference(), "amount", calculation.getCappedAmount()),
                actor.getId(), null);
        return receipt;
    }

    private String fullName(Internship internship) {
        if (internship.getCandidate() == null) {
            return "—";
        }
        return internship.getCandidate().getFirstName() + " " + internship.getCandidate().getLastName();
    }

    private String nationalId(Internship internship) {
        if (internship.getCandidate() == null || internship.getCandidate().getNationalIdEncrypted() == null
                || internship.getCandidate().getNationalIdEncrypted().isBlank()) {
            return "—";
        }
        return internship.getCandidate().getNationalIdEncrypted();
    }

    private String university(Internship internship) {
        if (internship.getCandidate() == null || internship.getCandidate().getUniversity() == null
                || internship.getCandidate().getUniversity().getName() == null) {
            return "—";
        }
        return internship.getCandidate().getUniversity().getName();
    }

    private List<PdfDocumentRenderer.ContentBlock> toBlocks(PdfTemplateProvider.StructuredTemplate template) {
        List<PdfDocumentRenderer.ContentBlock> blocks = new ArrayList<>();
        for (String paragraph : template.introParagraphs()) {
            blocks.add(new PdfDocumentRenderer.ContentBlock.Paragraph(paragraph));
        }
        if (!template.detailRows().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (PdfTemplateProvider.TableRow row : template.detailRows()) {
                rows.add(List.of(row.label(), row.value()));
            }
            blocks.add(new PdfDocumentRenderer.ContentBlock.DetailsTable(List.of(), rows));
        }
        for (String paragraph : template.bodyParagraphs()) {
            blocks.add(new PdfDocumentRenderer.ContentBlock.Paragraph(paragraph));
        }
        return blocks;
    }

    private String prettyType(Internship internship) {
        if (internship.getType() == null) {
            return "—";
        }
        return switch (internship.getType()) {
            case OBSERVATION -> "d'observation";
            case PERFECTIONNEMENT -> "de perfectionnement";
            case PFE -> "de projet de fin d'études (PFE)";
        };
    }

    private FileAsset storeGeneratedPdf(byte[] pdf, String fileName, User uploadedBy) {
        String checksum = sha256Hex(pdf);
        String storageKey;
        try (InputStream in = new ByteArrayInputStream(pdf)) {
            storageKey = fileStorageService.store(in, fileName, "application/pdf");
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist generated PDF " + fileName, e);
        }
        // Generated bytes come from our own renderer + bundled logo (no untrusted
        // input), so upload-time Tika/malware validation does not apply here.
        return fileAssetRepository.save(
                new FileAsset(storageKey, fileName, checksum, "application/pdf", (long) pdf.length, uploadedBy));
    }

    private void createLinkedDocument(FileAsset fileAsset, DocumentType type) {
        int year = Year.now().getValue();
        String prefix = "DOC-" + year + "-";
        String reference = String.format("%s%05d", prefix, documentRepository.countByReferencePrefix(prefix) + 1);
        Document document = documentRepository.save(new Document(reference, type));
        documentVersionRepository.save(new DocumentVersion(
                document, fileAsset, 1, fileAsset.getOriginalFileName(), fileAsset.getChecksum()));
    }

    private String nextCaseReference() {
        int year = Year.now().getValue();
        String base = "FC-" + year + "-";
        long sequence = financeCaseRepository.countByReferencePrefix(base) + 1;
        String reference;
        do {
            reference = String.format("%s%05d", base, sequence);
            sequence++;
        } while (financeCaseRepository.existsByReference(reference));
        return reference;
    }

    private String nextReceiptReference() {
        int year = Year.now().getValue();
        String base = "PAY-" + year + "-";
        long sequence = receiptRepository.countByReferencePrefix(base) + 1;
        String reference;
        do {
            reference = String.format("%s%05d", base, sequence);
            sequence++;
        } while (receiptRepository.existsByReference(reference));
        return reference;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private FinanceCaseResponse toResponse(FinanceCase financeCase) {
        List<PaymentCalculation> history = calculationRepository
                .findAllByFinanceCaseIdOrderByCalculationSequenceAsc(financeCase.getId());
        PaymentCalculationResponse calculation = history.isEmpty() ? null
                : PaymentCalculationResponse.from(history.get(history.size() - 1));
        List<FinanceCaseDocumentResponse> documents = financeCaseDocumentRepository
                .findByFinanceCaseId(financeCase.getId()).stream()
                .map(FinanceCaseDocumentResponse::from)
                .toList();
        List<PaymentApprovalResponse> approvals = approvalRepository
                .findByFinanceCaseIdOrderByDecisionSequenceAsc(financeCase.getId()).stream()
                .map(PaymentApprovalResponse::from)
                .toList();
        String receiptReference = receiptRepository.findByFinanceCaseId(financeCase.getId())
                .map(PaymentReceipt::getReference)
                .orElse(null);
        UUID workflowInstanceId = null;
        try {
            workflowInstanceId = workflowService.getPaymentWorkflowState(financeCase.getId()).id();
        } catch (ResourceNotFoundException e) {
            log.debug("No payment workflow instance yet for case {}", financeCase.getReference());
        }
        return FinanceCaseResponse.from(financeCase, calculation, documents, approvals,
                receiptReference, workflowInstanceId);
    }
}
