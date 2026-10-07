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
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T11 — the assistant wire contract the mobile app decodes.
 *
 * <p>The mobile client reads the authoritative {@code responseText} field of
 * {@code AiAnalysisResultResponse}; a historical client build read a
 * non-existent {@code displayText} key and rendered every successful answer
 * as "unavailable". These tests pin the wire shape on the backend side so a
 * rename can never silently break the app again (the Dart half lives in
 * {@code assistant_flow_test.dart}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T11 — Assistant response contract (responseText wire shape)")
class AssistantResponseContractTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String internToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User internUser = userRepository.saveAndFlush(new User("t11_intern_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        User supUser = userRepository.saveAndFlush(new User("t11_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        University uni = universityRepository.saveAndFlush(new University("UNI_T11_" + suffix, "T11 Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("T11" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Assist", "Ant", internUser.getEmail(), cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("T11" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_T11_" + suffix, "T11 Dept", "T11"));
        Employee supervisor = new Employee("EMP_T11_" + suffix, "Sana", "Sup", dept);
        supervisor.setUser(supUser);
        supervisor = employeeRepository.saveAndFlush(supervisor);

        User hrUser = userRepository.saveAndFlush(new User("t11_hr_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        var hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "T11 project",
                "Ingénieur",
                false), hrPrincipal);

        internshipService.assign(internship.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "T11 assignment"), hrPrincipal);
    }

    @Test
    @DisplayName("C1: intern query answers in responseText, never displayText")
    void internAnswerUsesResponseText() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("question", "Comment organiser mes tâches ?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText", not(emptyOrNullString())))
                .andExpect(jsonPath("$.displayText").doesNotExist());
    }

    @Test
    @DisplayName("C2: supervisor query answers in the same responseText shape")
    void supervisorAnswerUsesResponseText() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("question", "Résume mes stagiaires"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText", not(emptyOrNullString())))
                .andExpect(jsonPath("$.displayText").doesNotExist());
    }

    @Test
    @DisplayName("C3: oversized question is refused with 400, not truncated")
    void oversizedQuestionIsRefused() throws Exception {
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("question", "x".repeat(2001)))))
                .andExpect(status().isBadRequest());
    }
}
