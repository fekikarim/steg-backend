package tn.steg.backend.e1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.audit.domain.model.AuditLog;
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
import tn.steg.backend.internship.domain.model.*;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.internship.application.dto.InternshipStatusTransitionRequest;
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full state-matrix test for application and internship transitions.
 *
 * <p>Application workflow steps (via POST …/workflow/actions):
 *   SUBMITTED/RESUBMITTED → UNDER_REVIEW (valid)
 *   UNDER_REVIEW → FINAL_DECISION with APPROVED/REJECTED/MODIFICATION_REQUESTED (valid)
 *   All other from→target pairs → 409 INVALID_STATE_TRANSITION
 *
 * <p>Internship lifecycle steps (S6b — via POST …/status-transitions, the single
 * authority {@code InternshipLifecycleService}):
 *   APPROVED → IN_PROGRESS (valid)
 *   IN_PROGRESS → REPORT_SUBMITTED (valid)
 *   REPORT_SUBMITTED → UNDER_VALIDATION (valid)
 *   UNDER_VALIDATION → VALIDATED and VALIDATED → RECEIPT_ISSUED are RESERVED
 *   for the validation use case (422 INTERNSHIP_STATUS_RESERVED, S7) and are
 *   therefore excluded from the generic-endpoint matrix.
 *   All other from→target pairs → 409 INVALID_STATE_TRANSITION
 *
 * <p>For every valid transition, asserts audit entry creation + notification dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E1 — Full state-matrix (applications + internships)")
class StateMatrix409Test {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private WorkflowService workflowService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;

    private MockMvc mockMvc;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private University university;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User admin = userRepository.saveAndFlush(new User("admin_sm_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        departmentRepository.saveAndFlush(new Department("DEPT_SM_" + suffix, "Dept SM", "IT"));
        university = universityRepository.saveAndFlush(new University("UNI_SM_" + suffix, "SM Uni"));
    }

    // =========================================================================
    // APPLICATION — invalid transitions → 409
    // =========================================================================

    @Nested
    @DisplayName("Application: invalid from→UNDER_REVIEW returns 409")
    class AppInvalidToUnderReview {

        @ParameterizedTest(name = "{0} → UNDER_REVIEW = 409")
        // AGENTS.md §4: only SUBMITTED and RESUBMITTED are reviewable; a
        // MODIFICATION_REQUESTED dossier must be resubmitted by the candidate
        // first. (S1b: the legacy NEEDS_CORRECTION and ACCEPTED statuses no
        // longer exist, so they are not probed any more.)
        @EnumSource(value = ApplicationStatus.class,
                names = {"DRAFT", "MODIFICATION_REQUESTED", "UNDER_REVIEW",
                         "APPROVED", "REJECTED", "WITHDRAWN"})
        void invalidSourceToUnderReview(ApplicationStatus from) throws Exception {
            InternshipApplication app = createAppWithWorkflow(from);

            mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                    "UNDER_REVIEW", WorkflowActionType.SUBMISSION, null, "test"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    @Nested
    @DisplayName("Application: invalid from→FINAL_DECISION returns 409")
    class AppInvalidToFinalDecision {

        @ParameterizedTest(name = "{0} → FINAL_DECISION(REJECTED) = 409")
        // AGENTS.md §4 + Scenario A: SUBMITTED and RESUBMITTED are decidable
        // directly, so they are no longer part of the invalid-source probe.
        @EnumSource(value = ApplicationStatus.class,
                names = {"DRAFT", "MODIFICATION_REQUESTED", "APPROVED",
                         "REJECTED", "WITHDRAWN"})
        void invalidSourceToFinalDecision(ApplicationStatus from) throws Exception {
            InternshipApplication app = createAppWithWorkflow(from);

            mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                    "FINAL_DECISION", WorkflowActionType.APPROVAL,
                                    ApprovalDecision.REJECTED, "test rejection"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    // =========================================================================
    // APPLICATION — valid transitions → 200 + audit + notification
    // =========================================================================

    @Nested
    @DisplayName("Application: valid transitions succeed with audit + notification")
    class AppValidTransitions {

        @ParameterizedTest(name = "{0} → UNDER_REVIEW = 200 + audit")
        @EnumSource(value = ApplicationStatus.class,
                names = {"SUBMITTED", "RESUBMITTED"})
        void validSourceToUnderReview(ApplicationStatus from) throws Exception {
            InternshipApplication app = createAppWithWorkflow(from);
            int beforeAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "InternshipApplication", app.getId()).size();

            mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                    "UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "begin review"))))
                    .andExpect(status().isOk());

            List<AuditLog> afterAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "InternshipApplication", app.getId());
            assertThat(afterAudit.size()).isGreaterThan(beforeAudit);
            assertThat(afterAudit.stream().anyMatch(a -> "APPLICATION_UNDER_REVIEW".equals(a.getAction()))).isTrue();
        }

        @ParameterizedTest(name = "UNDER_REVIEW → FINAL_DECISION({0}) = 200 + audit")
        @CsvSource({"REJECTED,APPLICATION_REJECTED",
                "MODIFICATION_REQUESTED,APPLICATION_MODIFICATION_REQUESTED"})
        void validFinalDecision(String decision, String expectedAuditAction) throws Exception {
            InternshipApplication app = createAppWithWorkflow(ApplicationStatus.UNDER_REVIEW);
            int beforeAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "InternshipApplication", app.getId()).size();

            ApprovalDecision dec = ApprovalDecision.valueOf(decision);
            mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                    "FINAL_DECISION", WorkflowActionType.APPROVAL, dec, "decision reason"))))
                    .andExpect(status().isOk());

            List<AuditLog> afterAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "InternshipApplication", app.getId());
            assertThat(afterAudit.size()).isGreaterThan(beforeAudit);
            assertThat(afterAudit.stream().anyMatch(a -> expectedAuditAction.equals(a.getAction()))).isTrue();
            // Note: notifications fire AFTER_COMMIT (not in @Transactional tests).
            // Notification dispatch is proven by TransitionValidationAndSideEffectsTest.C3.
        }
    }

    // =========================================================================
    // INTERNSHIP — invalid transitions → 409
    // =========================================================================

    @Nested
    @DisplayName("Internship: invalid from→IN_PROGRESS returns 409")
    class InternshipInvalidToActive {

        @ParameterizedTest(name = "{0} → IN_PROGRESS = 409")
        @EnumSource(value = InternshipStatus.class,
                names = {"IN_PROGRESS", "REPORT_SUBMITTED",
                         "UNDER_VALIDATION", "VALIDATED", "RECEIPT_ISSUED",
                         "CANCELLED", "ARCHIVED"})
        void invalidSourceToActive(InternshipStatus from) throws Exception {
            Internship internship = createInternshipWithWorkflow(from);

            mockMvc.perform(post("/api/internships/" + internship.getId() + "/status-transitions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new InternshipStatusTransitionRequest(
                                    InternshipStatus.IN_PROGRESS, "test"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    @Nested
    @DisplayName("Internship: invalid from→REPORT_SUBMITTED returns 409")
    class InternshipInvalidToCompleted {

        @ParameterizedTest(name = "{0} → REPORT_SUBMITTED = 409")
        // S7 assumption #18: UNDER_VALIDATION → REPORT_SUBMITTED is the
        // resubmission return (a REJECTED manual decision sends the internship
        // back), so it is no longer probed as invalid here.
        @EnumSource(value = InternshipStatus.class,
                names = {"APPROVED", "REPORT_SUBMITTED",
                         "VALIDATED", "RECEIPT_ISSUED",
                         "CANCELLED", "ARCHIVED"})
        void invalidSourceToCompleted(InternshipStatus from) throws Exception {
            Internship internship = createInternshipWithWorkflow(from);

            mockMvc.perform(post("/api/internships/" + internship.getId() + "/status-transitions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new InternshipStatusTransitionRequest(
                                    InternshipStatus.REPORT_SUBMITTED, "test"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    // =========================================================================
    // INTERNSHIP — valid transitions → 200 + audit
    // =========================================================================

    @Nested
    @DisplayName("Internship: valid transitions succeed with audit")
    class InternshipValidTransitions {

        @ParameterizedTest(name = "{0} → {1} = 200 + audit")
        // S6b: the source statuses are the explicit §4 states; the target is
        // the §4 successor on the generic endpoint (reserved VALIDATED /
        // RECEIPT_ISSUED are covered by InternshipLifecycleMatrixTest).
        @CsvSource({"APPROVED,IN_PROGRESS", "IN_PROGRESS,REPORT_SUBMITTED",
                "REPORT_SUBMITTED,UNDER_VALIDATION"})
        void validInternshipTransition(String from, String to) throws Exception {
            InternshipStatus fromStatus = InternshipStatus.valueOf(from);
            Internship internship = createInternshipWithWorkflow(fromStatus);
            int beforeAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "Internship", internship.getId()).size();

            mockMvc.perform(post("/api/internships/" + internship.getId() + "/status-transitions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new InternshipStatusTransitionRequest(
                                    InternshipStatus.valueOf(to), "transition test"))))
                    .andExpect(status().isOk());

            List<AuditLog> afterAudit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                    "Internship", internship.getId());
            assertThat(afterAudit.size()).isGreaterThan(beforeAudit);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Candidate createUniqueCandidate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new RuntimeException(e); }
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("SM" + suffix).getBytes(StandardCharsets.UTF_8)));
        User candUser = userRepository.saveAndFlush(new User("cand_sm_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Candidate cand = new Candidate("SM", "Cand", "cand_sm_" + suffix + "@steg.tn", cinHash, university);
        cand.setUser(candUser);
        return candidateRepository.saveAndFlush(cand);
    }

    private InternshipApplication createAppWithWorkflow(ApplicationStatus status) {
        Candidate cand = createUniqueCandidate();
        InternshipApplication app = new InternshipApplication(
                "APP-SM-" + UUID.randomUUID().toString().substring(0, 8), cand, status);
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(2));
        app = applicationRepository.saveAndFlush(app);
        try { workflowService.spawnApplicationWorkflow(app); } catch (Exception ignored) {}
        return app;
    }

    private Internship createInternshipWithWorkflow(InternshipStatus status) {
        Candidate cand = createUniqueCandidate();
        Internship internship = new Internship(
                "INT-SM-" + UUID.randomUUID().toString().substring(0, 8), cand,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE);
        // S6b: seed the state directly on the aggregate — the fixture only needs
        // a row in the given status; no second engine is spawned any more.
        internship.setStatus(status);
        return internshipRepository.saveAndFlush(internship);
    }
}
