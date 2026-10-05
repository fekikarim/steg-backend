package tn.steg.backend.finance.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.finance.domain.model.PaymentCalculation;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentCalculationRepository;
import tn.steg.backend.finance.domain.repository.PaymentReceiptRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.support.InternshipLifecycleFixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pre-check (a) — NO RECEIPT BEFORE VALIDATION (AGENTS.md §5.11 step 6).
 *
 * <p>There is exactly ONE receipt-minting core:
 * {@code FinanceService.issueOrReuseReceipt}. Both live callers (the S7
 * validation receipt and the finance-case approval {@code doApprove}) route
 * through it, so the §4 gate is enforced structurally at the core: a receipt
 * can be created only for a {@code VALIDATED} internship (RECEIPT_ISSUED =
 * already validated, its receipt is reused). Any caller — today's two or any
 * future path — is refused with a clear <b>409 {@code RECEIPT_BEFORE_VALIDATION}</b>.
 *
 * <p>Proven against real Postgres (Testcontainers). The test deliberately
 * downgrades an internship's status below VALIDATED after a finance case and a
 * calculation snapshot exist — the situation a buggy second path or a data fix
 * could create — and proves BOTH entry points refuse to mint a receipt.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Pre-check (a) — no receipt is ever issued before VALIDATION (Testcontainers)")
class ReceiptValidationGateTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 receipt gate dossier file content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};


    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipLifecycleService lifecycleService;
    @Autowired private FinanceCaseRepository financeCaseRepository;
    @Autowired private PaymentCalculationRepository calculationRepository;
    @Autowired private PaymentReceiptRepository receiptRepository;
    @Autowired private FinanceService financeService;
    @Autowired private DocumentService documentService;
    @Autowired private tn.steg.backend.workflow.application.WorkflowService workflowService;

    private UserPrincipal adminPrincipal;
    private UserPrincipal supervisorPrincipal;
    private UUID internshipId;
    private tn.steg.backend.internship.domain.repository.InternshipRepository internshipPort;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User hrUser = userRepository.saveAndFlush(new User("hr_rg_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_rg_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User internUser = userRepository.saveAndFlush(new User("int_rg_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));

        adminPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));
        supervisorPrincipal = new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_RG_" + suffix, "Receipt Gate Dept", "RG"));
        Employee supervisorEmployee = new Employee("EMP-RG-S-" + suffix, "Rg", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
        // requireDecider: every Admin/Supervisor deciding finance needs an employee profile.
        Employee adminEmployee = new Employee("EMP-RG-A-" + suffix, "Rg", "Admin", dept);
        adminEmployee.setUser(hrUser);
        employeeRepository.saveAndFlush(adminEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_RG_" + suffix, "RG University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("RG" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Rg", "Intern", "int_rg_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("RG" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "RG project", "Ingénieur", false), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "rg"), adminPrincipal);
        InternshipLifecycleFixture.startAndValidate(
                lifecycleService,
                (tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository,
                internship.getId(), adminPrincipal);
        internshipId = internship.getId();
        internshipPort = (tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository;
    }

    private FinanceCase caseWithCalculation() {
        FinanceCase financeCase = financeCaseRepository.save(new FinanceCase(
                "FC-RG-" + UUID.randomUUID().toString().substring(0, 8),
                internshipPort.findById(internshipId).orElseThrow()));
        calculationRepository.save(new PaymentCalculation(
                financeCase, 3, 3, new java.math.BigDecimal("50.000"),
                new java.math.BigDecimal("150.000"), new java.math.BigDecimal("150.000"),
                false, "TND", 1));
        // Live approval flow needs its payment workflow instance (spawned by openFinanceCase).
        workflowService.spawnPaymentWorkflow(financeCase);
        return financeCase;
    }

    @Test
    @DisplayName("a receipt is minted only for a VALIDATED internship — anything less is 409 RECEIPT_BEFORE_VALIDATION")
    void noReceiptBeforeValidation() {
        FinanceCase financeCase = caseWithCalculation();

        // The internship is VALIDATED from the fixture: the S7 receipt succeeds
        // and flips the internship to RECEIPT_ISSUED (the only minting path).
        var view = financeService.generateValidationReceipt(internshipId, adminPrincipal);
        assertThat(view.reference()).startsWith("PAY-");
        // Scoped to THIS case: the shared Testcontainers database holds receipts
        // from the other finance tests, so a global PAY- count is meaningless.
        PaymentReceipt minted = receiptRepository.findByFinanceCaseId(financeCase.getId()).orElseThrow();
        assertThat(minted.getReference()).isEqualTo(view.reference());

        // Status tampered below VALIDATED (data fix / buggy path): the core
        // refuses to mint anything new — and still refuses even though a case
        // without a receipt exists.
        Internship internship = internshipPort.findById(internshipId).orElseThrow();
        internship.setStatus(InternshipStatus.IN_PROGRESS);
        internshipRepository.saveAndFlush(internship);

        assertThatThrownBy(() -> financeService.generateValidationReceipt(internshipId, adminPrincipal))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> assertThat(((ConflictException) thrown).getErrorCode())
                        .isEqualTo("INTERNSHIP_NOT_VALIDATED"))
                .hasMessageContaining("IN_PROGRESS");
        // The rejected call minted nothing: still exactly the one receipt above.
        assertThat(receiptRepository.findByFinanceCaseId(financeCase.getId()))
                .get()
                .satisfies(receipt -> assertThat(receipt.getReference()).isEqualTo(minted.getReference()));
    }

    @Test
    @DisplayName("the doApprove finance path cannot issue a receipt for a non-VALIDATED internship (409)")
    void financeApproveCannotIssueReceiptBeforeValidation() {
        FinanceCase financeCase = caseWithCalculation();
        financeCase.setStatus(FinanceCaseStatus.READY_FOR_DECISION);
        financeCaseRepository.save(financeCase);

        // Downgrade below VALIDATED, then push the case through the approval
        // preconditions (documents + workflow) so the ONLY thing standing
        // between the approval and a receipt is the gate itself.
        Internship internship = internshipPort.findById(internshipId).orElseThrow();
        internship.setStatus(InternshipStatus.UNDER_VALIDATION);
        internshipRepository.saveAndFlush(internship);

        java.util.UUID cin = documentService.uploadDocument(
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "cin.png", "image/png", PNG_BYTES),
                DocumentType.CIN_COPY, supervisorPrincipal).id();
        java.util.UUID application = documentService.uploadDocument(
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "app.pdf", "application/pdf", PDF_BYTES),
                DocumentType.INTERNSHIP_APPLICATION, supervisorPrincipal).id();
        java.util.UUID assignment = documentService.uploadDocument(
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "assign.pdf", "application/pdf", PDF_BYTES),
                DocumentType.ASSIGNMENT_LETTER, supervisorPrincipal).id();
        java.util.UUID report = documentService.uploadDocument(
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "report.pdf", "application/pdf", PDF_BYTES),
                DocumentType.STEG_INTERNSHIP_REPORT, supervisorPrincipal).id();
        for (java.util.UUID docId : List.of(cin, application, assignment, report)) {
            financeService.attachDocument(financeCase.getId(),
                    new tn.steg.backend.finance.application.dto.AttachFinanceDocumentRequest(docId, true), adminPrincipal);
            financeService.reviewDocument(financeCase.getId(), docId,
                    new tn.steg.backend.finance.application.dto.ReviewFinanceDocumentRequest(
                            tn.steg.backend.document.domain.model.DocumentVerificationStatus.VERIFIED, null), adminPrincipal);
        }
        // The certificate precondition (E1.3) is deliberately not met —
        // the receipt gate fires while the dossier is complete but the
        // internship was tampered below VALIDATED.
        assertThatThrownBy(() -> financeService.approve(financeCase.getId(),
                        new tn.steg.backend.finance.application.dto.PaymentDecisionRequest("ok"), adminPrincipal))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> assertThat(((ConflictException) thrown).getErrorCode())
                        .isEqualTo("RECEIPT_BEFORE_VALIDATION"))
                .hasMessageContaining("UNDER_VALIDATION");
        // Nothing was minted and the case is not approved.
        assertThat(receiptRepository.findByFinanceCaseId(financeCase.getId())).isEmpty();
        FinanceCase reloaded = financeCaseRepository.findById(financeCase.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(FinanceCaseStatus.APPROVED);
    }
}
