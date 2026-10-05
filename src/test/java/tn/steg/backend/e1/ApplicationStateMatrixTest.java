package tn.steg.backend.e1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application state matrix, written BY HAND from AGENTS.md §4 — never
 * derived from the guard implementation.
 *
 * <pre>
 *   from                                  action                        result
 *   DRAFT                                 candidate submit              SUBMITTED
 *   MODIFICATION_REQUESTED                candidate resubmit            RESUBMITTED
 *   SUBMITTED | RESUBMITTED               staff begin review            UNDER_REVIEW
 *   SUBMITTED | RESUBMITTED | UNDER_REVIEW staff approve                APPROVED
 *   SUBMITTED | RESUBMITTED | UNDER_REVIEW staff deny (reason)         REJECTED
 *   SUBMITTED | RESUBMITTED | UNDER_REVIEW staff request modification  MODIFICATION_REQUESTED
 * </pre>
 *
 * <p>Allowed pairs must succeed (200) and produce the audit entry and the
 * notification with the correct recipients; every other pair must return
 * HTTP 409 {@code INVALID_STATE_TRANSITION}. Approving directly from
 * SUBMITTED is required by Scenario A (the Admin approves a fresh submission);
 * UNDER_REVIEW stays valid for two-step reviews.
 *
 * <p>Deliberately NOT {@code @Transactional}: notification listeners run
 * {@code BEFORE_COMMIT}, so each probe must commit for its audit/notification
 * side effects to be observable. Probes use fresh candidates so committed rows
 * never collide.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1 — Application state matrix (AGENTS.md §4)")
class ApplicationStateMatrixTest {

    /** Hand-written allowed pairs: "from|action". */
    private static final Set<String> ALLOWED = Set.of(
            "DRAFT|SUBMIT",
            "MODIFICATION_REQUESTED|RESUBMIT",
            "SUBMITTED|REVIEW", "RESUBMITTED|REVIEW",
            "SUBMITTED|APPROVE", "RESUBMITTED|APPROVE", "UNDER_REVIEW|APPROVE",
            "SUBMITTED|REJECT", "RESUBMITTED|REJECT", "UNDER_REVIEW|REJECT",
            "SUBMITTED|MODIFY", "RESUBMITTED|MODIFY", "UNDER_REVIEW|MODIFY");

    /** Every application status a matrix probe can start from (legacy ones included). */
    private static final List<ApplicationStatus> ALL_STATUSES = List.of(
            ApplicationStatus.DRAFT,
            ApplicationStatus.SUBMITTED,
            ApplicationStatus.RESUBMITTED,
            ApplicationStatus.UNDER_REVIEW,
            ApplicationStatus.MODIFICATION_REQUESTED,
            ApplicationStatus.APPROVED,
            ApplicationStatus.REJECTED,
            ApplicationStatus.WITHDRAWN,
            ApplicationStatus.APPROVED,
            ApplicationStatus.MODIFICATION_REQUESTED);

    private static final List<String> STAFF_ACTIONS = List.of("REVIEW", "APPROVE", "REJECT", "MODIFY");

    /** A probe row with its candidate identity resolved before the session closes. */
    private record AppProbe(InternshipApplication app, UUID candidateUserId, String candidateEmail) {
    }

    /** Candidate identity captured while the creating session is still open. */
    private record CandidateIdentity(Candidate candidate, UUID userId, String email) {
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private WorkflowService workflowService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private Department department;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = userRepository.saveAndFlush(new User("admin_matrix_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        if (adminRole != null) {
            adminUser.getAssignedRoles().add(adminRole);
            adminUser = userRepository.saveAndFlush(adminUser);
        }
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        department = departmentRepository.saveAndFlush(new Department("DEPT_MX_" + suffix, "Dept Matrix", "IT"));
    }

    // =========================================================================
    // Allowed pairs: succeed + audit + notification recipients
    // =========================================================================

    @ParameterizedTest(name = "{0} --{1}--> audit={2} candidate={3} admin={4}")
    @CsvSource({
            "DRAFT,SUBMIT,APPLICATION_SUBMITTED,true,true",
            "MODIFICATION_REQUESTED,RESUBMIT,APPLICATION_RESUBMITTED,false,true",
            "SUBMITTED,REVIEW,APPLICATION_UNDER_REVIEW,false,false",
            "RESUBMITTED,REVIEW,APPLICATION_UNDER_REVIEW,false,false",
            "SUBMITTED,APPROVE,APPLICATION_ACCEPTED,true,false",
            "RESUBMITTED,APPROVE,APPLICATION_ACCEPTED,true,false",
            "UNDER_REVIEW,APPROVE,APPLICATION_ACCEPTED,true,false",
            "SUBMITTED,REJECT,APPLICATION_REJECTED,true,false",
            "RESUBMITTED,REJECT,APPLICATION_REJECTED,true,false",
            "UNDER_REVIEW,REJECT,APPLICATION_REJECTED,true,false",
            "SUBMITTED,MODIFY,APPLICATION_MODIFICATION_REQUESTED,true,false",
            "RESUBMITTED,MODIFY,APPLICATION_MODIFICATION_REQUESTED,true,false",
            "UNDER_REVIEW,MODIFY,APPLICATION_MODIFICATION_REQUESTED,true,false"})
    @DisplayName("allowed transition succeeds with its audit entry and notifications")
    void allowedTransitionSucceeds(String from, String action,
                                   String expectedAudit, boolean candidateNotified, boolean adminNotified) throws Exception {
        AppProbe probe = createApp(ApplicationStatus.valueOf(from));

        perform(probe, action).andExpect(status().isOk());

        assertThat(auditActions(probe.app().getId()))
                .as("audit entry for %s --%s-->", from, action)
                .contains(expectedAudit);
        if (candidateNotified) {
            assertThat(notificationReached(probe.app().getId(), probe.candidateUserId()))
                    .as("candidate must be notified for %s --%s-->", from, action).isTrue();
        }
        if (adminNotified) {
            assertThat(notificationReached(probe.app().getId(), adminUser.getId()))
                    .as("every active Admin must be notified for %s --%s-->", from, action).isTrue();
        }
    }

    // =========================================================================
    // Every other pair: 409
    // =========================================================================

    @Test
    @DisplayName("all non-allowed workflow pairs return 409 INVALID_STATE_TRANSITION")
    void nonAllowedStaffPairsReturn409() throws Exception {
        for (ApplicationStatus from : ALL_STATUSES) {
            for (String action : STAFF_ACTIONS) {
                if (ALLOWED.contains(from.name() + "|" + action)) {
                    continue;
                }
                perform(createApp(from), action)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
            }
        }
    }

    @Test
    @DisplayName("candidate submit is only valid from DRAFT; every other source is 409")
    void submitOnlyFromDraft() throws Exception {
        for (ApplicationStatus from : ALL_STATUSES) {
            if (from == ApplicationStatus.DRAFT) {
                continue;
            }
            perform(createApp(from), "SUBMIT")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    @Test
    @DisplayName("candidate resubmit is only valid from MODIFICATION_REQUESTED; every other source is 409")
    void resubmitOnlyFromModificationRequested() throws Exception {
        for (ApplicationStatus from : ALL_STATUSES) {
            if (from == ApplicationStatus.MODIFICATION_REQUESTED) {
                continue;
            }
            perform(createApp(from), "RESUBMIT")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
        }
    }

    // =========================================================================
    // Atomic approval endpoint accepts a fresh SUBMITTED application (Scenario A)
    // =========================================================================

    @Test
    @DisplayName("atomic approval from SUBMITTED creates the internship with the Admin as supervisor")
    void atomicApprovalAcceptsFreshSubmission() throws Exception {
        AppProbe probe = createApp(ApplicationStatus.SUBMITTED);

        String body = mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisorUserId").value(adminUser.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        UUID internshipId = UUID.fromString(objectMapper.readTree(body).get("internship").get("id").asText());
        Internship internship = internshipRepository.findById(internshipId).orElseThrow();
        assertThat(internship.getSupervisorUser().getId()).isEqualTo(adminUser.getId());
        assertThat(auditActions(probe.app().getId()))
                .contains("APPLICATION_ACCEPTED", "APPLICATION_APPROVED_WITH_SUPERVISOR");
        // The approver is the supervisor: he is notified as supervisor, and the
        // candidate receives the acceptance notification.
        assertThat(notificationReached(probe.app().getId(), probe.candidateUserId())).isTrue();
    }

    // =========================================================================
    // §5.3: the approve response is the ONE place credentials come back
    // =========================================================================

    @Test
    @DisplayName("atomic approval of an obligatory PFE returns the one-time credentials with Cache-Control: no-store")
    void approvalReturnsOneTimeCredentialsOnce() throws Exception {
        AppProbe probe = createApp(ApplicationStatus.SUBMITTED);
        // Obligatory PFE (> 3 months) is the §5.3 provisioning trigger.
        InternshipApplication app = probe.app();
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(4));
        applicationRepository.saveAndFlush(app);

        mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.supervisorUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.credentialEmail").isNotEmpty())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
    }

    @Test
    @DisplayName("approval reuses an existing INTERN account: one identity, no duplicate role, no password reset")
    void approvalReusesExistingInternAccount() throws Exception {
        AppProbe probe = createApp(ApplicationStatus.SUBMITTED);
        InternshipApplication app = probe.app();
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(4));
        applicationRepository.saveAndFlush(app);

        // §5.4 allows an account to exist BEFORE approval (manual creation).
        // Mutate it inside a transaction so the role collection is initialized.
        new TransactionTemplate(transactionManager).execute(status -> {
            User user = userPort().findById(probe.candidateUserId()).orElseThrow();
            user.getAssignedRoles().add(roleRepository.findByCode("INTERN").orElseThrow());
            user.setPasswordHash("already-provisioned-hash");
            user.setMustChangePassword(false);
            return userRepository.saveAndFlush(user);
        });

        mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest(
                                        null, department.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialEmail").value(probe.candidateEmail()))
                // already provisioned: no second one-time secret is minted
                .andExpect(jsonPath("$.temporaryPassword").value(org.hamcrest.Matchers.nullValue()));

        User reloaded = userPort().findById(probe.candidateUserId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo("already-provisioned-hash");
        assertThat(reloaded.getMustChangePassword()).isFalse();
        Integer internRoleCount = new TransactionTemplate(transactionManager).execute(status -> {
            User user = userPort().findById(probe.candidateUserId()).orElseThrow();
            return (int) user.getAssignedRoles().stream()
                    .filter(role -> "INTERN".equalsIgnoreCase(role.getCode()))
                    .count();
        });
        assertThat(internRoleCount).isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The infrastructure repository also implements the JPA interface, so
     *  findById is ambiguous without an explicit domain-port view. */
    private tn.steg.backend.iam.domain.repository.UserRepository userPort() {
        return userRepository;
    }

    private AppProbe createApp(ApplicationStatus status) {
        CandidateIdentity identity = newCandidate();
        InternshipApplication app = new InternshipApplication(
                "APP-MX-" + UUID.randomUUID().toString().substring(0, 8), identity.candidate(), status);
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(2));
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);
        return new AppProbe(app, identity.userId(), identity.email());
    }

    /** One fresh candidate per probe: a candidate may only hold one active application. */
    private CandidateIdentity newCandidate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try {
            User user = userRepository.saveAndFlush(new User(
                    "cand_mx_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
            UUID userId = user.getId();
            String email = user.getEmail();
            University uni = universityRepository.saveAndFlush(
                    new University("UNI_MX2_" + suffix, "Matrix Uni " + suffix));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest(("MX2" + suffix).getBytes(StandardCharsets.UTF_8)));
            Candidate fresh = new Candidate("Matrix", "Candidate " + suffix, email, cinHash, uni);
            fresh.setUser(user);
            Candidate saved = candidateRepository.saveAndFlush(fresh);
            return new CandidateIdentity(saved, userId, email);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions perform(AppProbe probe, String action) throws Exception {
        String candidateToken = jwtService.generateAccessToken(
                probe.candidateUserId(), probe.candidateEmail(), List.of("ROLE_CANDIDATE"));
        return switch (action) {
            case "SUBMIT" -> mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/submit")
                    .header("Authorization", "Bearer " + candidateToken));
            case "RESUBMIT" -> mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/resubmit")
                    .header("Authorization", "Bearer " + candidateToken));
            case "REVIEW" -> workflowAction(probe, new WorkflowTransitionRequest(
                    "UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "debut examen"));
            case "APPROVE" -> workflowAction(probe, new WorkflowTransitionRequest(
                    "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.APPROVED, null));
            case "REJECT" -> workflowAction(probe, new WorkflowTransitionRequest(
                    "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.REJECTED, "dossier incomplet"));
            case "MODIFY" -> workflowAction(probe, new WorkflowTransitionRequest(
                    "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.MODIFICATION_REQUESTED,
                    "merci de corriger le dossier"));
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private ResultActions workflowAction(AppProbe probe, WorkflowTransitionRequest request) throws Exception {
        return mockMvc.perform(post("/api/applications/" + probe.app().getId() + "/workflow/actions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private List<String> auditActions(UUID applicationId) {
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("InternshipApplication", applicationId)
                .stream().map(AuditLog::getAction).toList();
    }

    private boolean notificationReached(UUID applicationId, UUID recipientId) {
        return notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("InternshipApplication", applicationId)
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .anyMatch(d -> d.getRecipient() != null && recipientId.equals(d.getRecipient().getId()));
    }
}
