package tn.steg.backend.finance.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.audit.infrastructure.persistence.AuditLogRepository;
import tn.steg.backend.certificate.application.CertificateService;
import tn.steg.backend.certificate.domain.repository.CertificateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.infrastructure.persistence.DocumentRepository;
import tn.steg.backend.document.infrastructure.persistence.DocumentVersionRepository;
import tn.steg.backend.document.infrastructure.persistence.FileAssetRepository;
import tn.steg.backend.finance.application.dto.AttachFinanceDocumentRequest;
import tn.steg.backend.finance.application.dto.PaymentDecisionRequest;
import tn.steg.backend.finance.application.dto.ReviewFinanceDocumentRequest;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.finance.domain.model.PaymentApproval;
import tn.steg.backend.finance.domain.model.PaymentCalculation;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentApprovalRepository;
import tn.steg.backend.finance.domain.repository.PaymentCalculationRepository;
import tn.steg.backend.finance.domain.repository.PaymentReceiptRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production gate: idempotency under true parallel load (Phase A11 hardening).
 *
 * <p>Deliberately NOT {@code @Transactional}: every racing call runs in its own
 * transaction — exactly like concurrent production requests — so the
 * pessimistic case lock, the decision guards and the uniqueness backstops are
 * genuinely exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Finance Concurrency & Idempotency Tests (A11 hardening)")
class FinanceConcurrencyTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 finance dossier file content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FinanceService financeService;

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private FinanceCaseRepository financeCaseRepository;

    @Autowired
    private PaymentCalculationRepository calculationRepository;

    @Autowired
    private PaymentApprovalRepository approvalRepository;

    @Autowired
    private PaymentReceiptRepository receiptRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private FileAssetRepository fileAssetRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private UserPrincipal financePrincipal;
    private UserPrincipal supervisorPrincipal;
    private UUID internshipId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User hrUser = userRepository.saveAndFlush(new User("hr_fx_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User financeUser = userRepository.saveAndFlush(new User("fin_fx_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_fx_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User internUser = userRepository.saveAndFlush(new User("int_fx_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));

        financePrincipal = new UserPrincipal(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));
        supervisorPrincipal = new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_FX_" + suffix, "Fx Dept", "FX"));
        Employee supervisorEmployee = new Employee("EMP-FX-S-" + suffix, "Fx", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
        Employee financeEmployee = new Employee("EMP-FX-F-" + suffix, "Fx", "Fin", dept);
        financeEmployee.setUser(financeUser);
        financeEmployee = employeeRepository.saveAndFlush(financeEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_FX_" + suffix, "Fx Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("FX" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Fx", "Intern", "int_fx_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("FX" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Fx project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "fx"), hrPrincipal);
        workflowService.transitionInternship(internship.getId(),
                new WorkflowTransitionRequest("COMPLETED", WorkflowActionType.COMPLETION, null, "done"), hrPrincipal);
        internshipId = internship.getId();
    }

    private UUID readyCase() {
        UUID caseId = financeService.openFinanceCase(internshipId, financePrincipal).id();
        upload("cin.png", "image/png", PNG_BYTES, DocumentType.CIN_COPY, caseId);
        upload("app.pdf", "application/pdf", PDF_BYTES, DocumentType.INTERNSHIP_APPLICATION, caseId);
        upload("assign.pdf", "application/pdf", PDF_BYTES, DocumentType.ASSIGNMENT_LETTER, caseId);
        upload("report.pdf", "application/pdf", PDF_BYTES, DocumentType.STEG_INTERNSHIP_REPORT, caseId);
        return caseId;
    }

    private void upload(String name, String contentType, byte[] bytes, DocumentType type, UUID caseId) {
        MultipartFile file = new MockMultipartFile("file", name, contentType, bytes);
        UUID docId = documentService.uploadDocument(file, type, supervisorPrincipal).id();
        financeService.attachDocument(caseId, new AttachFinanceDocumentRequest(docId, true), financePrincipal);
        financeService.reviewDocument(caseId, docId,
                new ReviewFinanceDocumentRequest(tn.steg.backend.document.domain.model.DocumentVerificationStatus.VERIFIED, null),
                financePrincipal);
    }

    private static void assertBusinessError(Future<?> future, String... codes) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            throw new AssertionError("Expected a business error but the call succeeded");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            boolean dataConflict = cause instanceof org.springframework.dao.DataIntegrityViolationException;
            boolean businessRule = cause instanceof BusinessRuleException
                    && (codes.length == 0 || List.of(codes).contains(((BusinessRuleException) cause).getErrorCode()));
            assertThat(dataConflict || businessRule)
                    .as("Expected safe conflict error, got: " + cause)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("10 concurrent approvals against real PostgreSQL yield exactly one success, "
            + "one receipt, one FileAsset, one audit record and one notification")
    void concurrentApprovalsYieldOneSuccess() throws Exception {
        UUID caseId = readyCase();
        long assetsBefore = fileAssetRepository.count();
        long documentsBefore = documentRepository.count();
        long notificationsBefore = notificationRepository.count();

        int concurrency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return financeService.approve(caseId, new PaymentDecisionRequest("ok"), financePrincipal);
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int successes = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException e) {
                // Every losing request must fail safely with a business/concurrency
                // error — never a raw/unhandled exception leaking to the caller.
                boolean safeConflict = e.getCause() instanceof BusinessRuleException
                        || e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException;
                assertThat(safeConflict).as("Expected safe conflict error, got: " + e.getCause()).isTrue();
            }
        }

        // Exactly one success out of 10 truly concurrent approval attempts.
        assertThat(successes).isEqualTo(1);

        // Direct PostgreSQL verification: no duplicate rows anywhere in the chain.
        assertThat(approvalRepository.findByFinanceCaseIdOrderByDecisionSequenceAsc(caseId)).hasSize(1);
        assertThat(receiptRepository.findByFinanceCaseId(caseId)).isPresent();
        var receipt = receiptRepository.findByFinanceCaseId(caseId).orElseThrow();
        assertThat(financeCaseRepository.findById(caseId).orElseThrow().getStatus())
                .isEqualTo(FinanceCaseStatus.APPROVED);

        // Exactly one new FileAsset was created (the receipt PDF) — no duplicate
        // receipt files despite 10 racing generation attempts.
        assertThat(fileAssetRepository.count()).isEqualTo(assetsBefore + 1);
        assertThat(receipt.getPdfFile()).isNotNull();
        assertThat(((tn.steg.backend.document.domain.repository.FileAssetRepository) fileAssetRepository)
                .findById(receipt.getPdfFile().getId())).isPresent();

        // Exactly one new Document row (PAYMENT_RECEIPT) was linked alongside
        // the FileAsset — CertificateService/FinanceService always create both
        // (Document + DocumentVersion) in the same transaction via
        // createLinkedDocument(...), pointing at the exact same FileAsset. This
        // test is deliberately not @Transactional (see class Javadoc), so the
        // lazy `document` association on DocumentVersion is not navigated here
        // — the file-asset id alone is enough to prove exactly one linked
        // version was created for this receipt's own PDF.
        assertThat(documentRepository.count()).isEqualTo(documentsBefore + 1);
        long receiptDocumentVersions = documentVersionRepository.findAll().stream()
                .filter(v -> v.getFile().getId().equals(receipt.getPdfFile().getId()))
                .count();
        assertThat(receiptDocumentVersions).isEqualTo(1);

        // Exactly one audit record for the approval (no duplicate audit writes
        // from the 9 losing attempts, which must never reach the audited step).
        List<tn.steg.backend.audit.domain.model.AuditLog> approvalAudits =
                auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("FinanceCase", caseId).stream()
                        .filter(a -> "FINANCE_CASE_APPROVED".equals(a.getAction()))
                        .toList();
        assertThat(approvalAudits).hasSize(1);

        // Exactly one notification was created as a consequence of the single
        // successful approval (BEFORE_COMMIT semantics: the 9 rolled-back
        // attempts never publish an event).
        long financeCaseNotifications = notificationRepository.findAll().stream()
                .filter(n -> "FinanceCase".equals(n.getRelatedEntityType()) && caseId.equals(n.getRelatedEntityId()))
                .count();
        assertThat(financeCaseNotifications).isEqualTo(1);
        assertThat(notificationRepository.count()).isEqualTo(notificationsBefore + 1);
    }

    @Test
    @DisplayName("Concurrent recalculations yield gapless unique snapshot sequences")
    void concurrentRecalculationsYieldUniqueSequences() throws Exception {
        UUID caseId = readyCase();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return financeService.recalculate(caseId, financePrincipal);
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        List<PaymentCalculation> history =
                calculationRepository.findAllByFinanceCaseIdOrderByCalculationSequenceAsc(caseId);
        assertThat(history).hasSize(4); // opening snapshot + 3 recalculations
        assertThat(history.stream().map(PaymentCalculation::getCalculationSequence).toList())
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("Approve racing recalculation stays consistent: one approval, receipt matches a snapshot")
    void approveVsRecalculateStaysConsistent() throws Exception {
        UUID caseId = readyCase();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<?> approveFuture = pool.submit(() -> {
            gate.await(10, TimeUnit.SECONDS);
            return financeService.approve(caseId, new PaymentDecisionRequest("ok"), financePrincipal);
        });
        Future<?> recalcFuture = pool.submit(() -> {
            gate.await(10, TimeUnit.SECONDS);
            return financeService.recalculate(caseId, financePrincipal);
        });
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // Exactly one terminal outcome for the approval path; recalculation either
        // ran first (approval then uses its snapshot) or was cleanly rejected.
        int approvals = 0;
        try {
            approveFuture.get(30, TimeUnit.SECONDS);
            approvals = 1;
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class);
        }
        try {
            recalcFuture.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class);
        }

        var financeCase = financeCaseRepository.findById(caseId).orElseThrow();
        List<PaymentApproval> approvalRows = approvalRepository.findByFinanceCaseIdOrderByDecisionSequenceAsc(caseId);
        if (approvals == 1) {
            assertThat(financeCase.getStatus()).isEqualTo(FinanceCaseStatus.APPROVED);
            assertThat(approvalRows).hasSize(1);
            var receipt = receiptRepository.findByFinanceCaseId(caseId).orElseThrow();
            List<PaymentCalculation> history =
                    calculationRepository.findAllByFinanceCaseIdOrderByCalculationSequenceAsc(caseId);
            assertThat(history.stream().map(PaymentCalculation::getCappedAmount).toList())
                    .contains(receipt.getAmount());
        } else {
            assertThat(financeCase.getStatus()).isNotEqualTo(FinanceCaseStatus.APPROVED);
            assertThat(approvalRows).isEmpty();
            assertThat(receiptRepository.findByFinanceCaseId(caseId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Approve racing reject yields exactly one terminal state, never both")
    void approveVsRejectNoContradiction() throws Exception {
        UUID caseId = readyCase();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<?> approveFuture = pool.submit(() -> {
            gate.await(10, TimeUnit.SECONDS);
            return financeService.approve(caseId, new PaymentDecisionRequest("ok"), financePrincipal);
        });
        Future<?> rejectFuture = pool.submit(() -> {
            gate.await(10, TimeUnit.SECONDS);
            return financeService.reject(caseId, new PaymentDecisionRequest("inconsistent dossier"), financePrincipal);
        });
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int wins = 0;
        for (Future<?> future : List.of(approveFuture, rejectFuture)) {
            try {
                future.get(30, TimeUnit.SECONDS);
                wins++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class);
            }
        }
        assertThat(wins).isEqualTo(1);

        var financeCase = financeCaseRepository.findById(caseId).orElseThrow();
        var approvals = approvalRepository.findByFinanceCaseIdOrderByDecisionSequenceAsc(caseId);
        assertThat(approvals).hasSize(1);
        if (financeCase.getStatus() == FinanceCaseStatus.APPROVED) {
            assertThat(receiptRepository.findByFinanceCaseId(caseId)).isPresent();
        } else {
            assertThat(financeCase.getStatus()).isEqualTo(FinanceCaseStatus.REJECTED);
            assertThat(receiptRepository.findByFinanceCaseId(caseId)).isEmpty();
            assertThat(approvals.get(0).getComment()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Concurrent certificate generations yield exactly one artifact")
    void concurrentCertificateGenerationsYieldOneArtifact() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return certificateService.generateCertificate(internshipId, supervisorPrincipal);
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int successes = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOfAny(
                        BusinessRuleException.class,
                        org.springframework.dao.DataIntegrityViolationException.class);
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(certificateRepository.findByInternshipId(internshipId)).hasSize(1);
    }
}
