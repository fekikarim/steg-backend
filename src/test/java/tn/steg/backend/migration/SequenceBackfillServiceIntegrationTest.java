package tn.steg.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.finance.application.FinanceService;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentReceiptRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.support.InternshipLifecycleFixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V52 real-service proof (the Spring-driven half of the
 * {@code MigrationChainIntegrationTest} sequence-backfill chain, which stays
 * raw Flyway because Spring cannot run between its own migrations).
 *
 * <p>Order of operations reproduces the PRODUCTION upgrade exactly:
 * <ol>
 *   <li>raw Flyway migrates a fresh Testcontainers database to V50;</li>
 *   <li>count-based legacy reference rows are inserted (same seeder as the
 *       raw chain test — FC-/PAY-/DOC- rows including the exact low current-year
 *       numbers a START 1 sequence re-mints and the high maxima);</li>
 *   <li>raw Flyway migrates to latest, executing V51 (sequences START 1) and
 *       V52 (backfill above every existing suffix);</li>
 *   <li>the REAL services then mint new references:
 *       {@code FinanceService.generateValidationReceipt} (mints an FC- case
 *       reference AND a PAY- receipt reference) and
 *       {@code DocumentService.uploadDocument} (mints a DOC- reference).</li>
 * </ol>
 *
 * <p>Without V52 the receipt call in step 4 dies on the {@code finance_cases}
 * reference UNIQUE constraint with {@code FC-2026-00001} (a seeded legacy row)
 * — the production bug this slice fixes. With V52 every minted number is
 * strictly greater than every seeded suffix and no unique constraint fires.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("V52 — a V51-upgraded database mints new references through the REAL services without collision")
class SequenceBackfillServiceIntegrationTest {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4 sequence backfill upload content".getBytes(StandardCharsets.UTF_8);

    @Autowired private PostgreSQLContainer postgres;

    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipLifecycleService lifecycleService;
    @Autowired private FinanceService financeService;
    @Autowired private DocumentService documentService;
    @Autowired private FinanceCaseRepository financeCaseRepository;
    @Autowired private PaymentReceiptRepository receiptRepository;

    private UserPrincipal fixtureAdminPrincipal;

    @Test
    @DisplayName("after V51+V52 the real receipt and document services mint references above every legacy number")
    void upgradedDatabaseMintsNonCollidingReferencesThroughRealServices() throws Exception {
        migrateTo("50");
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            LegacyReferenceRows.seed(c);
        }
        migrateTo(null);

        UUID internshipId = validatedInternshipFixture();

        // REAL service path #1: mints the finance case (FC-) and the receipt (PAY-).
        var view = financeService.generateValidationReceipt(internshipId, fixtureAdminPrincipal);
        assertThat(view.reference()).startsWith("PAY-");
        assertThat(suffix(view.reference())).as("PAY suffix above legacy max")
                .isGreaterThan(LegacyReferenceRows.MAX_PAY_SUFFIX);
        assertThat(receiptRepository.findByFinanceCaseId(UUID.fromString(view.financeCaseId())))
                .as("the minted receipt row is the one the service returned")
                .get()
                .satisfies(receipt -> assertThat(receipt.getReference()).isEqualTo(view.reference()));
        FinanceCase financeCase = financeCaseRepository.findById(UUID.fromString(view.financeCaseId())).orElseThrow();
        assertThat(financeCase.getReference()).startsWith("FC-");
        assertThat(suffix(financeCase.getReference())).as("FC suffix above legacy max")
                .isGreaterThan(LegacyReferenceRows.MAX_FC_SUFFIX);

        // REAL service path #2: mints a document reference (DOC-).
        var doc = documentService.uploadDocument(
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "report.pdf", "application/pdf", PDF_BYTES),
                DocumentType.STEG_INTERNSHIP_REPORT, fixtureAdminPrincipal);
        assertThat(doc.reference()).startsWith("DOC-");
        assertThat(suffix(doc.reference())).as("DOC suffix above legacy max")
                .isGreaterThan(LegacyReferenceRows.MAX_DOC_SUFFIX);
    }

    private void migrateTo(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    /** ReceiptValidationGateTest fixture: Admin employee profile + VALIDATED obligatory internship. */
    private UUID validatedInternshipFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User hrUser = userRepository.saveAndFlush(new User("hr_sb_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_sb_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User internUser = userRepository.saveAndFlush(new User("int_sb_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_SB_" + suffix, "Backfill Dept", "SB"));
        Employee adminEmployee = new Employee("EMP-SB-A-" + suffix, "Sb", "Admin", dept);
        adminEmployee.setUser(hrUser);
        employeeRepository.saveAndFlush(adminEmployee);
        Employee supervisorEmployee = new Employee("EMP-SB-S-" + suffix, "Sb", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_SB_" + suffix, "Backfill University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("SB" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Sb", "Intern", "int_sb_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("SB" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        UserPrincipal adminPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));
        fixtureAdminPrincipal = adminPrincipal;
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "SB project", "Ingénieur", false), adminPrincipal);
        internshipService.assign(created.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "sb"), adminPrincipal);
        InternshipLifecycleFixture.startAndValidate(
                lifecycleService,
                (tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository,
                created.id(), adminPrincipal);
        return created.id();
    }

    private static long suffix(String reference) {
        return Long.parseLong(reference.substring(reference.lastIndexOf('-') + 1));
    }
}
