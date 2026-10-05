package tn.steg.backend.e1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

/**
 * E1.3 — Input-validation and side-effect proofs for state-machine transitions.
 *
 * <p>Section A: blank/whitespace denial reason → 409.
 * Section B: blank/whitespace modification message → 409.
 * Section C: valid transitions emit audit entries and notifications.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1.3 — Transition validation and side-effect probes")
class TransitionValidationAndSideEffectsTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private WorkflowService workflowService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private Candidate candidate;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User admin = userRepository.saveAndFlush(new User("adm_e13_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        departmentRepository.saveAndFlush(new Department("DEPT_E13_" + suffix, "Dept E13", "IT"));
        University uni = universityRepository.saveAndFlush(new University("UNI_E13_" + suffix, "E13 Uni"));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(
                digest.digest(("E13" + suffix).getBytes(StandardCharsets.UTF_8)));
        User candUser = userRepository.saveAndFlush(new User("cand_e13_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        candidate = new Candidate("E13", "Candidate", "e13_" + suffix + "@steg.tn", cinHash, uni);
        candidate.setUser(candUser);
        candidate = candidateRepository.saveAndFlush(candidate);
    }

    // =========================================================================
    // A. Blank/whitespace denial reason → 409
    // =========================================================================

    @ParameterizedTest(name = "Blank denial reason [{0}] -> 409")
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("A1: blank/whitespace denial reason returns 409 INVALID_STATE_TRANSITION")
    void blankDenialReasonReturns409(String blankComment) throws Exception {
        InternshipApplication app = submittedUnderReviewApp();
        String payload = objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.REJECTED, blankComment));

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("A2: null denial reason returns 409 INVALID_STATE_TRANSITION")
    void nullDenialReasonReturns409() throws Exception {
        InternshipApplication app = submittedUnderReviewApp();
        String payload = "{\"targetStepCode\":\"FINAL_DECISION\",\"actionType\":\"APPROVAL\","
                + "\"decision\":\"REJECTED\",\"comment\":null}";

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
    }

    // =========================================================================
    // B. Blank/whitespace modification message → 409
    // =========================================================================

    @ParameterizedTest(name = "Blank modification message [{0}] -> 409")
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("B1: blank/whitespace modification message returns 409 INVALID_STATE_TRANSITION")
    void blankModificationMessageReturns409(String blankComment) throws Exception {
        InternshipApplication app = submittedUnderReviewApp();
        String payload = objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.MODIFICATION_REQUESTED, blankComment));

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
    }

    // =========================================================================
    // C. Valid transitions: audit entries and notifications
    // =========================================================================

    @Test
    @DisplayName("C1: REJECTED transition emits APPLICATION_REJECTED audit entry")
    void rejectionEmitsAuditEntry() throws Exception {
        InternshipApplication app = submittedUnderReviewApp();
        int beforeCount = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).size();

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "FINAL_DECISION", WorkflowActionType.APPROVAL,
                                ApprovalDecision.REJECTED, "Dossier incomplet"))))
                .andExpect(status().isOk());

        boolean hasAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).stream()
                .skip(beforeCount)
                .anyMatch(a -> "APPLICATION_REJECTED".equals(a.getAction()));
        assertThat(hasAudit).as("APPLICATION_REJECTED audit entry must be created").isTrue();
    }

    @Test
    @DisplayName("C2: MODIFICATION_REQUESTED transition emits APPLICATION_MODIFICATION_REQUESTED audit entry")
    void needsCorrectionEmitsAuditEntry() throws Exception {
        InternshipApplication app = submittedUnderReviewApp();
        int beforeCount = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).size();

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "FINAL_DECISION", WorkflowActionType.APPROVAL,
                                ApprovalDecision.MODIFICATION_REQUESTED, "Manque le releve de notes"))))
                .andExpect(status().isOk());

        boolean hasAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).stream()
                .skip(beforeCount)
                .anyMatch(a -> "APPLICATION_MODIFICATION_REQUESTED".equals(a.getAction()));
        assertThat(hasAudit).as("APPLICATION_MODIFICATION_REQUESTED audit entry must be created").isTrue();
    }

    @Test
    @DisplayName("C3: REJECTED transition emits a notification")
    void rejectionEmitsNotification() {
        InternshipApplication app = submittedUnderReviewApp();
        long beforeNotifs = notificationRepository.count();

        workflowService.transitionApplication(app.getId(),
                new WorkflowTransitionRequest("FINAL_DECISION", WorkflowActionType.APPROVAL,
                        ApprovalDecision.REJECTED, "Dossier incomplet — service test"),
                adminPrincipal);

        assertThat(notificationRepository.count())
                .as("A notification must be emitted on rejection")
                .isGreaterThan(beforeNotifs);
    }

    @Test
    @DisplayName("C4: UNDER_REVIEW transition emits APPLICATION_UNDER_REVIEW audit entry")
    void underReviewEmitsAuditEntry() throws Exception {
        InternshipApplication app = submittedApp();
        int beforeCount = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).size();

        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "Debut examen"))))
                .andExpect(status().isOk());

        boolean hasAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", app.getId()).stream()
                .skip(beforeCount)
                .anyMatch(a -> "APPLICATION_UNDER_REVIEW".equals(a.getAction()));
        assertThat(hasAudit).as("APPLICATION_UNDER_REVIEW audit entry must be created").isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InternshipApplication submittedApp() {
        InternshipApplication app = new InternshipApplication(
                "APP-E13-" + UUID.randomUUID().toString().substring(0, 8), candidate, ApplicationStatus.SUBMITTED);
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(2));
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);
        return app;
    }

    private InternshipApplication submittedUnderReviewApp() {
        InternshipApplication app = submittedApp();
        workflowService.transitionApplication(app.getId(),
                new WorkflowTransitionRequest("UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "exam"),
                adminPrincipal);
        return applicationRepository.findByReference(app.getReference()).orElseThrow();
    }
}
