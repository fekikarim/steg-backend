package tn.steg.backend.workflow.interfaces.rest;

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
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Workflow Engine Integration Tests")
class WorkflowControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private User createStaffUser(String email, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseGet(() -> roleRepository.saveAndFlush(new Role(roleCode, roleCode, null)));
        User user = new User(email, "hashed_password", UserStatus.ACTIVE);
        user.setAssignedRoles(Set.of(role));
        return userRepository.saveAndFlush(user);
    }

    private Candidate createTestCandidate(String email, String cin, University uni) throws Exception {
        User user = new User(email, "hashed_password", UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));

        Candidate candidate = new Candidate("First", "Last", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Workflow Lifecycle: Spawn on submit, review transitions to UNDER_REVIEW then APPROVED")
    void applicationWorkflowLifecycle() throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_WF_1", "Test Uni WF"));
        Candidate candidate = createTestCandidate("cand_wf@test.tn", "11223399", uni);
        User hrUser = createStaffUser("hr_wf@steg.com.tn", "ROLE_HR");

        String hrToken = jwtService.generateAccessToken(
                hrUser.getId(),
                hrUser.getEmail(),
                List.of("ROLE_HR")
        );

        // Candidate submits application
        InternshipApplication app = new InternshipApplication("APP-2026-99999", candidate, ApplicationStatus.SUBMITTED);
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);

        // 1. Get current workflow state
        mockMvc.perform(get("/api/applications/" + app.getId() + "/workflow")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStepCode").value("SUBMITTED"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 2. Transition to UNDER_REVIEW
        WorkflowTransitionRequest step1Request = new WorkflowTransitionRequest(
                "UNDER_REVIEW",
                WorkflowActionType.VALIDATION,
                null,
                "Documents look complete, starting review"
        );

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(step1Request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepCode").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.type").value("VALIDATION"));

        // 3. Transition to FINAL_DECISION (APPROVED)
        WorkflowTransitionRequest step2Request = new WorkflowTransitionRequest(
                "FINAL_DECISION",
                WorkflowActionType.APPROVAL,
                ApprovalDecision.APPROVED,
                "Candidate accepted for Summer 2026"
        );

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(step2Request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepCode").value("FINAL_DECISION"))
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        // 4. Verify completed state
        mockMvc.perform(get("/api/applications/" + app.getId() + "/workflow")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Workflow Guard: Illegal direct transition from SUBMITTED directly to FINAL_DECISION is rejected")
    void illegalTransitionRejectedByGuard() throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_WF_2", "Test Uni WF 2"));
        Candidate candidate = createTestCandidate("cand_wf2@test.tn", "11223398", uni);
        User hrUser = createStaffUser("hr_wf2@steg.com.tn", "ROLE_HR");

        String hrToken = jwtService.generateAccessToken(
                hrUser.getId(),
                hrUser.getEmail(),
                List.of("ROLE_HR")
        );

        InternshipApplication app = new InternshipApplication("APP-2026-99998", candidate, ApplicationStatus.SUBMITTED);
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);

        // Attempt direct jump to FINAL_DECISION while app is still SUBMITTED -> 422 Unprocessable Entity
        WorkflowTransitionRequest illegalRequest = new WorkflowTransitionRequest(
                "FINAL_DECISION",
                WorkflowActionType.APPROVAL,
                ApprovalDecision.APPROVED,
                "Illegal shortcut"
        );

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(illegalRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ILLEGAL_WORKFLOW_TRANSITION"));
    }
}
