package tn.steg.backend.evaluation.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.comment.application.dto.CommentRequest;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.evaluation.application.dto.EvaluationCriterionRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationScoreRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationTaskReviewRequest;
import tn.steg.backend.evaluation.application.dto.EvaluationTemplateRequest;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Evaluation Module Integration Tests (A8)")
class EvaluationIntegrationTest {

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
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User hrUser;
    private User supervisorUser;
    private User supervisor2User;
    private User internUser;
    private User otherUser;

    private String hrToken;
    private String supervisorToken;
    private String supervisor2Token;
    private String internToken;
    private String otherToken;

    private Employee supervisorEmployee;
    private Employee supervisor2Employee;
    private Candidate internCandidate;
    private Internship testInternship;
    private Task testTask;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 1. Users & Tokens
        hrUser = userRepository.saveAndFlush(new User("hr_eval@steg.com", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        supervisorUser = userRepository.saveAndFlush(new User("supervisor_eval@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        supervisor2User = userRepository.saveAndFlush(new User("supervisor2_eval@steg.com", "hash", UserStatus.ACTIVE));
        supervisor2Token = jwtService.generateAccessToken(supervisor2User.getId(), supervisor2User.getEmail(), List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("intern_eval@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));

        otherUser = userRepository.saveAndFlush(new User("other_eval@steg.com", "hash", UserStatus.ACTIVE));
        otherToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail(), List.of("ROLE_CANDIDATE"));

        // 2. Department & Employees
        Department dept = departmentRepository.saveAndFlush(new Department("DIR_INFO_EVAL", "Direction Informatique", "IT"));

        supervisorEmployee = new Employee("EMP-EVAL-1", "Moncef", "Trabelsi", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        supervisor2Employee = new Employee("EMP-EVAL-2", "Nabil", "Gharbi", dept);
        supervisor2Employee.setUser(supervisor2User);
        supervisor2Employee = employeeRepository.saveAndFlush(supervisor2Employee);

        // 3. Candidate
        University uni = universityRepository.saveAndFlush(new University("INSAT_EVAL", "INSAT Tunis"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest("11223344".getBytes(StandardCharsets.UTF_8)));
        internCandidate = new Candidate("Ali", "Ben Salah", "intern_eval@steg.com", cinHash, uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("11223344");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        // 4. Internship & Assignment via Service
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "STEG Companion Project",
                "Ingénieur",
                false
        ), hrPrincipal);

        testInternship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();

        internshipService.assign(testInternship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(), LocalDate.now().minusDays(30), LocalDate.now().plusDays(60), "Project assignment"
        ), hrPrincipal);

        // 5. Task for evaluation
        testTask = new Task(testInternship, supervisorUser, "Implement Auth Screen", "Build authentication UI");
        testTask.setDueDate(LocalDate.now().plusDays(2));
        testTask.setStatus(TaskStatus.COMPLETED);
        testTask = taskRepository.saveAndFlush(testTask);
    }

    // -------------------------------------------------------------------------
    // Evaluation Templates & Criteria
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HR can create evaluation template and criteria")
    void hrCanCreateTemplateAndCriteria() throws Exception {
        EvaluationTemplateRequest tReq = new EvaluationTemplateRequest(
                "Final Engineering Evaluation",
                "Template for engineering internship evaluations"
        );

        MvcResult tResult = mockMvc.perform(post("/api/evaluation-templates")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Final Engineering Evaluation"))
                .andReturn();

        JsonNode tNode = objectMapper.readTree(tResult.getResponse().getContentAsString());
        UUID templateId = UUID.fromString(tNode.get("id").asText());

        // Add criterion
        EvaluationCriterionRequest cReq = new EvaluationCriterionRequest(
                "Technical Execution",
                "Quality of code and architecture",
                new BigDecimal("40.0"),
                new BigDecimal("20.0")
        );

        mockMvc.perform(post("/api/evaluation-templates/" + templateId + "/criteria")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Technical Execution"))
                .andExpect(jsonPath("$.weight").value(40.0));

        // Candidate / Intern cannot create template (Forbidden)
        mockMvc.perform(post("/api/evaluation-templates")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tReq)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Evaluations & Score Calculation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Active supervisor can create evaluation and compute weighted score correctly")
    void supervisorCanEvaluateAndComputeScore() throws Exception {
        // 1. Create custom template with 2 criteria (weights: 60, 40)
        EvaluationTemplateRequest tReq = new EvaluationTemplateRequest(
                "Mid-Term Template", "Evaluation template"
        );
        MvcResult tRes = mockMvc.perform(post("/api/evaluation-templates")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID templateId = UUID.fromString(objectMapper.readTree(tRes.getResponse().getContentAsString()).get("id").asText());

        // Crit 1: Technical (Weight 60, Max 20)
        MvcResult c1Res = mockMvc.perform(post("/api/evaluation-templates/" + templateId + "/criteria")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EvaluationCriterionRequest("Tech", "Tech desc", new BigDecimal("60.0"), new BigDecimal("20.0")))))
                .andExpect(status().isCreated()).andReturn();
        UUID crit1Id = UUID.fromString(objectMapper.readTree(c1Res.getResponse().getContentAsString()).get("id").asText());

        // Crit 2: Soft Skills (Weight 40, Max 20)
        MvcResult c2Res = mockMvc.perform(post("/api/evaluation-templates/" + templateId + "/criteria")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EvaluationCriterionRequest("Soft", "Soft desc", new BigDecimal("40.0"), new BigDecimal("20.0")))))
                .andExpect(status().isCreated()).andReturn();
        UUID crit2Id = UUID.fromString(objectMapper.readTree(c2Res.getResponse().getContentAsString()).get("id").asText());

        // 2. Create Evaluation by active supervisor
        EvaluationRequest evalReq = new EvaluationRequest(
                templateId,
                EvaluationType.MID_TERM,
                LocalDate.now(),
                "Overall good performance during first half."
        );

        MvcResult evalRes = mockMvc.perform(post("/api/internships/" + testInternship.getId() + "/evaluations")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evalReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MID_TERM"))
                .andExpect(jsonPath("$.totalScore").isEmpty())
                .andReturn();

        UUID evalId = UUID.fromString(objectMapper.readTree(evalRes.getResponse().getContentAsString()).get("id").asText());

        // 3. Submit Scores: Crit 1 = 18/20, Crit 2 = 14/20
        // Weighted calculation:
        // ((18/20 * 60) + (14/20 * 40)) / (60 + 40) * 20
        // = (54 + 28) / 100 * 20 = 82 / 100 * 20 = 16.40
        List<EvaluationScoreRequest> scores = List.of(
                new EvaluationScoreRequest(crit1Id, new BigDecimal("18.00"), "Excellent technical grasp"),
                new EvaluationScoreRequest(crit2Id, new BigDecimal("14.00"), "Good communication")
        );

        mockMvc.perform(post("/api/evaluations/" + evalId + "/scores")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scores)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Check evaluation totalScore updated to 16.40
        mockMvc.perform(get("/api/evaluations/" + evalId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalScore").value(16.40));

        // 4. Inactive / other supervisor cannot submit scores (Forbidden)
        mockMvc.perform(post("/api/evaluations/" + evalId + "/scores")
                        .header("Authorization", "Bearer " + supervisor2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scores)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Task Review Evaluation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Supervisor can review specific tasks and prevent duplicate reviews")
    void supervisorCanReviewTasks() throws Exception {
        EvaluationRequest evalReq = new EvaluationRequest(
                null,
                EvaluationType.WEEKLY,
                LocalDate.now(),
                "Weekly sprint evaluation"
        );

        MvcResult evalRes = mockMvc.perform(post("/api/internships/" + testInternship.getId() + "/evaluations")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evalReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID evalId = UUID.fromString(objectMapper.readTree(evalRes.getResponse().getContentAsString()).get("id").asText());

        // Add task review
        EvaluationTaskReviewRequest taskReviewReq = new EvaluationTaskReviewRequest(
                testTask.getId(),
                true,
                new BigDecimal("19.50"),
                "Auth screen delivered ahead of schedule with clean design."
        );

        mockMvc.perform(post("/api/evaluations/" + evalId + "/task-reviews")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskReviewReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskTitle").value("Implement Auth Screen"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.score").value(19.50));

        // Duplicate review for same task within same evaluation rejected (422)
        mockMvc.perform(post("/api/evaluations/" + evalId + "/task-reviews")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskReviewReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DUPLICATE_TASK_REVIEW"));
    }

    // -------------------------------------------------------------------------
    // Evaluation Comments
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Participants can comment on an evaluation")
    void participantsCanCommentOnEvaluation() throws Exception {
        EvaluationRequest evalReq = new EvaluationRequest(
                null,
                EvaluationType.FINAL,
                LocalDate.now(),
                "Final assessment"
        );

        MvcResult evalRes = mockMvc.perform(post("/api/internships/" + testInternship.getId() + "/evaluations")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evalReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID evalId = UUID.fromString(objectMapper.readTree(evalRes.getResponse().getContentAsString()).get("id").asText());

        // Intern comments on evaluation
        CommentRequest commentReq = new CommentRequest("Thank you for the constructive feedback throughout the internship!");
        mockMvc.perform(post("/api/evaluations/" + evalId + "/comments")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Thank you for the constructive feedback throughout the internship!"))
                .andExpect(jsonPath("$.authorEmail").value("intern_eval@steg.com"));

        // List evaluation comments
        mockMvc.perform(get("/api/evaluations/" + evalId + "/comments")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Non-participant cannot comment (Forbidden)
        mockMvc.perform(post("/api/evaluations/" + evalId + "/comments")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentReq)))
                .andExpect(status().isForbidden());
    }
}
