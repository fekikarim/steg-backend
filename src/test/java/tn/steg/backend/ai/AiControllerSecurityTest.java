package tn.steg.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;
import tn.steg.backend.ai.domain.model.AiRecommendation;
import tn.steg.backend.ai.infrastructure.persistence.AiAnalysisRepository;
import tn.steg.backend.ai.infrastructure.persistence.AiRecommendationRepository;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.infrastructure.persistence.FinanceCaseRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A12 closure — controller-level security tests for all five AI endpoints.
 *
 * <p>Covers, per endpoint: unauthenticated {@code 401}, wrong-role {@code 403},
 * authorized happy path, plus the A12-specific ownership rules (candidate
 * isolation, logbook participation, FINANCE-only finance analysis, Employee
 * authority for human review) and validation-envelope checks.
 *
 * <p>The AI provider stays disabled under the {@code test} profile, so happy
 * paths exercise the real service through its graceful-degradation branch
 * (HTTP {@code 200} with an empty recommendation list) without any network call.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A12 — AiController security (all 5 endpoints)")
class AiControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipAssignmentRepository assignmentRepository;

    @Autowired
    private FinanceCaseRepository financeCaseRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private AiAnalysisRepository aiAnalysisRepository;

    @Autowired
    private AiRecommendationRepository aiRecommendationRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private String hrToken;
    private String financeToken;
    private String adminToken;
    private String internToken;
    private String outsiderToken;
    private String supervisorToken;

    private Candidate internCandidate;
    private Candidate outsiderCandidate;
    private InternshipApplication application;
    private Internship internship;
    private FinanceCase financeCase;
    private AiRecommendation recommendation;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        User hrUser = userRepository.saveAndFlush(new User("aisec_hr@steg.tn", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        User financeUser = userRepository.saveAndFlush(new User("aisec_fin@steg.tn", "hash", UserStatus.ACTIVE));
        financeToken = jwtService.generateAccessToken(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));

        User adminUser = userRepository.saveAndFlush(new User("aisec_admin@steg.tn", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User supervisorUser = userRepository.saveAndFlush(new User("aisec_sup@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        User internUser = userRepository.saveAndFlush(new User("aisec_intern@steg.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));

        User outsiderUser = userRepository.saveAndFlush(new User("aisec_outsider@steg.tn", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(), List.of("ROLE_CANDIDATE"));

        Department dept = departmentRepository.saveAndFlush(new Department("AISEC-DIR", "AI Security Dept", "tests"));

        Employee hrEmployee = new Employee("AISEC-HR", "Hajer", "HR", dept);
        hrEmployee.setUser(hrUser);
        hrEmployee = employeeRepository.saveAndFlush(hrEmployee);

        Employee financeEmployee = new Employee("AISEC-FIN", "Karim", "Finance", dept);
        financeEmployee.setUser(financeUser);
        financeEmployee = employeeRepository.saveAndFlush(financeEmployee);

        Employee supervisorEmployee = new Employee("AISEC-SUP", "Leila", "Supervisor", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("AISEC-UNI", "AISEC University"));
        internCandidate = new Candidate("Aymen", "Intern", "aisec_intern@steg.tn", "HASH-AISEC-INTERN", uni);
        internCandidate.setUser(internUser);
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        outsiderCandidate = new Candidate("Syrine", "Outsider", "aisec_outsider@steg.tn", "HASH-AISEC-OUT", uni);
        outsiderCandidate.setUser(outsiderUser);
        outsiderCandidate = candidateRepository.saveAndFlush(outsiderCandidate);

        application = new InternshipApplication("APP-AISEC-1", internCandidate, ApplicationStatus.SUBMITTED);
        application = applicationRepository.saveAndFlush(application);

        internship = new Internship("INT-AISEC-1", internCandidate,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.ACTIVE);
        internship = internshipRepository.saveAndFlush(internship);

        InternshipAssignment assignment = new InternshipAssignment(internship, dept, supervisorEmployee, hrEmployee,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), AssignmentStatus.ACTIVE);
        ((org.springframework.data.jpa.repository.JpaRepository<InternshipAssignment, UUID>) assignmentRepository)
                .saveAndFlush(assignment);

        financeCase = new FinanceCase("FIN-AISEC-1", internship);
        financeCase = financeCaseRepository.saveAndFlush(financeCase);

        AiAnalysis analysis = new AiAnalysis(AiAnalysisType.APPLICATION_DOCUMENT_ANALYSIS,
                "InternshipApplication", application.getId(), "test-model");
        analysis.setRequestedBy(hrUser);
        analysis.setInputSummary("security-test");
        analysis.setOutputSummary("security-test-output");
        analysis = aiAnalysisRepository.saveAndFlush(analysis);
        recommendation = aiRecommendationRepository.saveAndFlush(new AiRecommendation(analysis, "advisory text"));
    }

    // ------------------------------------------------------------------
    // POST /api/ai/applications/{id}/analyze — ADMIN, HR
    // ------------------------------------------------------------------

    @Test
    @DisplayName("application analyze: unauthenticated -> 401")
    void applicationAnalyzeUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/applications/{id}/analyze", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("application analyze: CANDIDATE and FINANCE roles -> 403")
    void applicationAnalyzeWrongRole() throws Exception {
        mockMvc.perform(post("/api/ai/applications/{id}/analyze", application.getId())
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/applications/{id}/analyze", application.getId())
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("application analyze: HR and ADMIN reach the endpoint (degraded 200, CIN excluded)")
    void applicationAnalyzeAuthorized() throws Exception {
        mockMvc.perform(post("/api/ai/applications/{id}/analyze", application.getId())
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.cinExcluded").value(true))
                .andExpect(jsonPath("$.recommendations").isEmpty());
        mockMvc.perform(post("/api/ai/applications/{id}/analyze", application.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // POST /api/ai/finance-cases/{id}/analyze — ADMIN, FINANCE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("finance-case analyze: unauthenticated -> 401")
    void financeAnalyzeUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/finance-cases/{id}/analyze", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("finance-case analyze: HR and CANDIDATE roles -> 403 (FINANCE-only)")
    void financeAnalyzeWrongRole() throws Exception {
        mockMvc.perform(post("/api/ai/finance-cases/{id}/analyze", financeCase.getId())
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/finance-cases/{id}/analyze", financeCase.getId())
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("finance-case analyze: FINANCE reaches the endpoint (degraded 200)")
    void financeAnalyzeAuthorized() throws Exception {
        mockMvc.perform(post("/api/ai/finance-cases/{id}/analyze", financeCase.getId())
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.cinExcluded").value(true))
                .andExpect(jsonPath("$.recommendations").isEmpty());
    }

    // ------------------------------------------------------------------
    // POST /api/ai/internships/{id}/logbook/generate — ADMIN, HR, participant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("logbook generate: unauthenticated -> 401")
    void logbookUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/internships/{id}/logbook/generate", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logbook generate: non-participant candidate -> 403")
    void logbookOutsiderForbidden() throws Exception {
        mockMvc.perform(post("/api/ai/internships/{id}/logbook/generate", internship.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("logbook generate: intern, supervisor, and HR can reach the endpoint")
    void logbookParticipantsAuthorized() throws Exception {
        mockMvc.perform(post("/api/ai/internships/{id}/logbook/generate", internship.getId())
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.cinExcluded").value(true));
        mockMvc.perform(post("/api/ai/internships/{id}/logbook/generate", internship.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/internships/{id}/logbook/generate", internship.getId())
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // POST /api/ai/assistant/query — CANDIDATE (own context only)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("assistant query: unauthenticated -> 401")
    void assistantUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bonjour\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("assistant query: HR role -> 403")
    void assistantWrongRole() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bonjour\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("assistant query: each candidate only ever sees their own context")
    void assistantIsolatedPerCandidate() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", "Où en est mon dossier ?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.relatedEntityId").value(internCandidate.getId().toString()));
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", "Où en est mon dossier ?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.relatedEntityId").value(outsiderCandidate.getId().toString()));
    }

    @Test
    @DisplayName("assistant query: blank question -> 400 standard validation envelope")
    void assistantValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    // ------------------------------------------------------------------
    // POST /api/ai/recommendations/{id}/review — ADMIN, HR, FINANCE, SUPERVISOR + Employee
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recommendation review: unauthenticated -> 401")
    void reviewUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("recommendation review: CANDIDATE role -> 403")
    void reviewWrongRole() throws Exception {
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", recommendation.getId())
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recommendation review: ADMIN without Employee profile -> 404 (Employee authority required)")
    void reviewRequiresEmployeeAuthority() throws Exception {
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", recommendation.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("recommendation review: HR with Employee profile accepts the recommendation")
    void reviewHappyPath() throws Exception {
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", recommendation.getId())
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED_BY_HUMAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_BY_HUMAN"));
    }

    @Test
    @DisplayName("recommendation review: PROPOSED status rejected with 422; missing status -> 400 envelope")
    void reviewValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", recommendation.getId())
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROPOSED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        mockMvc.perform(post("/api/ai/recommendations/{id}/review", recommendation.getId())
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }
}
