package tn.steg.backend.reporting.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.infrastructure.persistence.FileAssetRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.finance.infrastructure.persistence.FinanceCaseRepository;
import tn.steg.backend.finance.infrastructure.persistence.PaymentReceiptRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A13 — Back Office reporting: authorization matrix and aggregate
 * correctness against a seeded data set. Everything asserted here is read-only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — ReportController auth + aggregate correctness")
class ReportControllerTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository internshipAssignmentRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private FinanceCaseRepository financeCaseRepository;
    @Autowired private PaymentReceiptRepository paymentReceiptRepository;
    @Autowired private FileAssetRepository fileAssetRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private final Map<String, User> roleUsers = new java.util.HashMap<>();

    private String adminToken;
    private String directorToken;
    private String hrToken;
    private String financeToken;
    private String candidateToken;

    private Department departmentAlpha;
    private Department departmentBeta;
    private UUID departmentAlphaId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        adminToken = tokenForRole("ADMIN");
        directorToken = tokenForRole("DIRECTOR");
        hrToken = tokenForRole("HR");
        financeToken = tokenForRole("FINANCE");
        candidateToken = tokenForRole("CANDIDATE");

        University uni = universityRepository.saveAndFlush(new University("RP-UNI", "Report University"));
        User candUser = roleUsers.get("CANDIDATE");
        Candidate candidate = new Candidate("Report", "Candidate", "report_candidate@steg.tn", "RP-000000", uni);
        candidate.setUser(candUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        User adminUser = roleUsers.get("ADMIN");

        departmentAlpha = departmentRepository.saveAndFlush(new Department("RP-DEV", "Development", "report dept A"));
        departmentBeta = departmentRepository.saveAndFlush(new Department("RP-OPS", "Operations", "report dept B"));
        departmentAlphaId = departmentAlpha.getId();

        Employee supAlpha = employeeRepository.saveAndFlush(
                new Employee("RP-EMP-1", "Super", "Alpha", departmentAlpha));
        Employee supBeta = employeeRepository.saveAndFlush(
                new Employee("RP-EMP-2", "Super", "Beta", departmentBeta));
        Employee adminEmployee = employeeRepository.saveAndFlush(
                new Employee("RP-EMP-3", "Chief", "Admin", departmentAlpha));
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        // 2 applications: DRAFT + ACCEPTED
        InternshipApplication draftApp = new InternshipApplication("APP-RP-DRAFT", candidate, ApplicationStatus.DRAFT);
        InternshipApplication acceptedApp = new InternshipApplication("APP-RP-ACC", candidate, ApplicationStatus.ACCEPTED);
        applicationRepository.saveAndFlush(draftApp);
        applicationRepository.saveAndFlush(acceptedApp);

        // 3 internships: (OBSERVATION, ACTIVE, dept A) assigned, (PERFECTIONNEMENT, COMPLETED, dept B) assigned, (OBSERVATION, PLANNED, unassigned)
        Internship internshipActive = internshipRepository.saveAndFlush(new Internship(
                "INT-RP-1", candidate, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE));
        internshipActive.setStatus(InternshipStatus.ACTIVE);

        Internship internshipCompleted = internshipRepository.saveAndFlush(new Internship(
                "INT-RP-2", candidate, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 20),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OPTIONAL));
        internshipCompleted.setStatus(InternshipStatus.COMPLETED);

        Internship internshipPlanned = internshipRepository.saveAndFlush(new Internship(
                "INT-RP-3", candidate, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE));
        internshipPlanned.setStatus(InternshipStatus.PLANNED);

        internshipAssignmentRepository.saveAndFlush(new InternshipAssignment(
                internshipActive, departmentAlpha, supAlpha, adminEmployee,
                LocalDate.of(2026, 6, 20), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                AssignmentStatus.ACTIVE));
        internshipAssignmentRepository.saveAndFlush(new InternshipAssignment(
                internshipCompleted, departmentBeta, supBeta, adminEmployee,
                LocalDate.of(2025, 12, 15), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 20),
                AssignmentStatus.ENDED));

        // Finance cases: one APPROVED + one REJECTED
        FinanceCase approvedCase = financeCaseRepository.saveAndFlush(new FinanceCase("FC-RP-1", internshipActive));
        approvedCase.setStatus(FinanceCaseStatus.APPROVED);
        FinanceCase rejectedCase = financeCaseRepository.saveAndFlush(new FinanceCase("FC-RP-2", internshipCompleted));
        rejectedCase.setStatus(FinanceCaseStatus.REJECTED);

        User uploader = adminUser;
        FileAsset pdf = fileAssetRepository.saveAndFlush(new FileAsset(
                "rp/" + UUID.randomUUID() + ".pdf", "rp-report.pdf", "sha256-rp", "application/pdf", 128L, uploader));

        PaymentReceipt receipt = new PaymentReceipt("RC-RP-1", approvedCase, pdf,
                new BigDecimal("800.00"), 2, "TND");
        receipt.setPaymentDate(LocalDate.of(2026, 8, 15));
        paymentReceiptRepository.saveAndFlush(receipt);
    }

    private String tokenForRole(String role) {
        User user = roleUsers.computeIfAbsent(role, r ->
                userRepository.saveAndFlush(new User("report_" + r.toLowerCase() + "@steg.tn", "hash", UserStatus.ACTIVE)));
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_" + role));
    }

    @Test
    @DisplayName("unauthenticated access to every report endpoint is rejected")
    void unauthenticatedRejected() throws Exception {
        for (String path : new String[]{"/api/reports/applications-by-status", "/api/reports/internships-by-type",
                "/api/reports/internships-by-status", "/api/reports/internships-by-department",
                "/api/reports/finance-cases-by-status", "/api/reports/payment-totals"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("CANDIDATE is forbidden from all report endpoints")
    void candidateForbiddenEverywhere() throws Exception {
        for (String path : new String[]{"/api/reports/applications-by-status", "/api/reports/internships-by-type",
                "/api/reports/internships-by-status", "/api/reports/internships-by-department",
                "/api/reports/finance-cases-by-status", "/api/reports/payment-totals"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + candidateToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("HR sees planning reports but is forbidden from treasury reports")
    void hrPlanningScope() throws Exception {
        mockMvc.perform(get("/api/reports/applications-by-status").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/internships-by-type").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/internships-by-status").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/internships-by-department").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/finance-cases-by-status").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/payment-totals").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FINANCE sees treasury reports but is forbidden from planning reports")
    void financeTreasuryScope() throws Exception {
        mockMvc.perform(get("/api/reports/applications-by-status").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/finance-cases-by-status").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/payment-totals").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN and DIRECTOR can read every report endpoint")
    void adminAndDirectorFullAccess() throws Exception {
        for (String path : new String[]{"/api/reports/applications-by-status", "/api/reports/internships-by-type",
                "/api/reports/internships-by-status", "/api/reports/internships-by-department",
                "/api/reports/finance-cases-by-status", "/api/reports/payment-totals"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + directorToken)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("applications-by-status returns DB-side grouped counts")
    void applicationStatusCounts() throws Exception {
        mockMvc.perform(get("/api/reports/applications-by-status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupName=='DRAFT')].count").value(1))
                .andExpect(jsonPath("$[?(@.groupName=='ACCEPTED')].count").value(1))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("internship reports return grouped counts incl. department rollup and unassigned exclusion")
    void internshipCounts() throws Exception {
        mockMvc.perform(get("/api/reports/internships-by-type").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupName=='OBSERVATION')].count").value(2))
                .andExpect(jsonPath("$[?(@.groupName=='PERFECTIONNEMENT')].count").value(1));

        mockMvc.perform(get("/api/reports/internships-by-status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupName=='ACTIVE')].count").value(1))
                .andExpect(jsonPath("$[?(@.groupName=='COMPLETED')].count").value(1))
                .andExpect(jsonPath("$[?(@.groupName=='PLANNED')].count").value(1));

        mockMvc.perform(get("/api/reports/internships-by-department").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupName=='RP-DEV')].count").value(1))
                .andExpect(jsonPath("$[?(@.groupName=='RP-OPS')].count").value(1))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("finance-cases-by-status returns grouped counts")
    void financeCaseCounts() throws Exception {
        mockMvc.perform(get("/api/reports/finance-cases-by-status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupName=='APPROVED')].count").value(1))
                .andExpect(jsonPath("$[?(@.groupName=='REJECTED')].count").value(1));
    }

    @Test
    @DisplayName("payment-totals rolls up amount+count by period and department, and supports department filter")
    void paymentTotals() throws Exception {
        mockMvc.perform(get("/api/reports/payment-totals").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].year").value(2026))
                .andExpect(jsonPath("$[0].month").value(8))
                .andExpect(jsonPath("$[0].departmentCode").value("RP-DEV"))
                .andExpect(jsonPath("$[0].totalAmount").value(800.00))
                .andExpect(jsonPath("$[0].receiptCount").value(1));

        mockMvc.perform(get("/api/reports/payment-totals").param("departmentId", departmentAlphaId.toString())
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].departmentCode").value("RP-DEV"));

        mockMvc.perform(get("/api/reports/payment-totals").param("departmentId", java.util.UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isNotFound());
    }
}