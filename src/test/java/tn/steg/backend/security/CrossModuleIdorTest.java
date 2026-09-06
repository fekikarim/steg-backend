package tn.steg.backend.security;

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
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.evaluation.application.dto.EvaluationCriterionRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationTemplateRequest;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A14 — fresh cross-tenant authorization verification across every module,
 * beyond the pre-existing messaging/document/application IDOR coverage.
 *
 * <p>Each test attempts to reach another tenant's resource by direct ID and asserts
 * a 403/404 (never data). The internship cases specifically guard the A14 fix for
 * the role-only read gate that previously leaked {@code candidateFullName}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("A14 — Cross-module IDOR guards")
class CrossModuleIdorTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String adminToken;
    private String hrToken;
    private String financeToken;
    private Department department;
    private Employee supervisorEmployee;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        User admin = userRepository.saveAndFlush(new User("idor_admin@test.tn", "hash", UserStatus.ACTIVE));
        adminToken = token(admin, "ROLE_ADMIN");
        User hr = userRepository.saveAndFlush(new User("idor_hr@test.tn", "hash", UserStatus.ACTIVE));
        hrToken = token(hr, "ROLE_HR");
        User finance = userRepository.saveAndFlush(new User("idor_fin@test.tn", "hash", UserStatus.ACTIVE));
        financeToken = token(finance, "ROLE_FINANCE");

        department = departmentRepository.saveAndFlush(new Department("IDOR_DEPT", "IDOR Department", "IDOR"));
        User supUser = userRepository.saveAndFlush(new User("idor_sup@test.tn", "hash", UserStatus.ACTIVE));
        supervisorEmployee = new Employee("IDOR-EMP-1", "Idor", "Sup", department);
        supervisorEmployee.setUser(supUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Candidate createCandidate(String email, String cin) throws Exception {
        University uni = universityRepository.saveAndFlush(
                new University("U_" + UUID.randomUUID().toString().substring(0, 8), "Uni"));
        User user = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Idor", "Cand", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.saveAndFlush(candidate);
    }

    private String candidateToken(Candidate candidate) {
        return token(candidate.getUser(), "ROLE_CANDIDATE");
    }

    private UUID createInternship(Candidate candidate) throws Exception {
        InternshipCreateManualRequest req = new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "IDOR subject",
                "Ingenieur",
                false);
        String body = mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void assignSupervisor(UUID internshipId) throws Exception {
        InternshipAssignmentRequest req = new InternshipAssignmentRequest(
                department.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60), "IDOR assignment");
        mockMvc.perform(post("/api/internships/" + internshipId + "/assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("internship reads: owner reads own, stranger gets 403 (no candidateFullName leak)")
    void internshipCrossCandidateReadBlocked() throws Exception {
        Candidate candA = createCandidate("idor_a@test.tn", "10000001");
        Candidate candB = createCandidate("idor_b@test.tn", "10000002");
        UUID internshipA = createInternship(candA);

        mockMvc.perform(get("/api/internships/" + internshipA)
                        .header("Authorization", "Bearer " + candidateToken(candA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateFullName").value("Idor Cand"));

        mockMvc.perform(get("/api/internships/" + internshipA)
                        .header("Authorization", "Bearer " + candidateToken(candB)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/internships/" + internshipA + "/classification")
                        .header("Authorization", "Bearer " + candidateToken(candB)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/internships/" + internshipA + "/classification")
                        .header("Authorization", "Bearer " + candidateToken(candA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("evaluations: only participants read; foreign supervisor cannot create")
    void evaluationCrossTenantBlocked() throws Exception {
        Candidate candA = createCandidate("idor_ea@test.tn", "20000001");
        Candidate candB = createCandidate("idor_eb@test.tn", "20000002");
        UUID internshipA = createInternship(candA);
        assignSupervisor(internshipA);

        String supToken = token(supervisorEmployee.getUser(), "ROLE_SUPERVISOR");
        User sup2User = userRepository.saveAndFlush(new User("idor_sup2@test.tn", "hash", UserStatus.ACTIVE));
        Employee sup2 = new Employee("IDOR-EMP-2", "Idor", "Sup2", department);
        sup2.setUser(sup2User);
        employeeRepository.saveAndFlush(sup2);
        String sup2Token = token(sup2User, "ROLE_SUPERVISOR");

        String templateBody = mockMvc.perform(post("/api/evaluation-templates")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EvaluationTemplateRequest("IDOR template", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(objectMapper.readTree(templateBody).get("id").asText());
        mockMvc.perform(post("/api/evaluation-templates/" + templateId + "/criteria")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EvaluationCriterionRequest("Tech", "d", new BigDecimal("100.0"), new BigDecimal("20.0")))))
                .andExpect(status().isCreated());

        String evalBody = mockMvc.perform(post("/api/internships/" + internshipA + "/evaluations")
                        .header("Authorization", "Bearer " + supToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EvaluationRequest(
                                templateId, EvaluationType.MID_TERM, LocalDate.now(), "Good"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID evalId = UUID.fromString(objectMapper.readTree(evalBody).get("id").asText());

        // Foreign supervisor cannot create on, nor read, another internship's evaluation.
        mockMvc.perform(post("/api/internships/" + internshipA + "/evaluations")
                        .header("Authorization", "Bearer " + sup2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EvaluationRequest(
                                templateId, EvaluationType.WEEKLY, LocalDate.now(), "X"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/evaluations/" + evalId)
                        .header("Authorization", "Bearer " + sup2Token))
                .andExpect(status().isForbidden());

        // Foreign intern cannot read it either; the owning intern can.
        mockMvc.perform(get("/api/evaluations/" + evalId)
                        .header("Authorization", "Bearer " + candidateToken(candB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/evaluations/" + evalId)
                        .header("Authorization", "Bearer " + candidateToken(candA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("finance cases: candidate and HR denied; finance sees 404 for unknown id, not data")
    void financeCaseAccessDeniedForNonFinance() throws Exception {
        Candidate cand = createCandidate("idor_f@test.tn", "30000001");
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/finance-cases")
                        .header("Authorization", "Bearer " + candidateToken(cand)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/finance-cases")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/finance-cases/" + unknown)
                        .header("Authorization", "Bearer " + candidateToken(cand)))
                .andExpect(status().isForbidden());

        // FINANCE role passes the gate: unknown id is a clean 404, list is reachable.
        mockMvc.perform(get("/api/finance-cases/" + unknown)
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk());
    }
}