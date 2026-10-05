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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.dto.InternshipStatusTransitionRequest;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full internship lifecycle matrix, written BY HAND from AGENTS.md §4 — never
 * derived from the service implementation.
 *
 * <pre>
 *   APPROVED → IN_PROGRESS → REPORT_SUBMITTED → UNDER_VALIDATION → VALIDATED → RECEIPT_ISSUED
 * </pre>
 *
 * <p>S6b: {@code VALIDATED} and {@code RECEIPT_ISSUED} are RESERVED for the
 * validation use case (AGENTS.md §5.11) and are rejected by the generic
 * {@code POST /api/internships/{id}/status-transitions} with 422
 * {@code INTERNSHIP_STATUS_RESERVED}. They are reachable only through the
 * manual validation decision and the receipt generation (S7), or — in tests —
 * through {@code InternshipLifecycleService.transition} directly (the
 * {@code InternshipLifecycleFixture}). So the generic-endpoint matrix below
 * proves the first three pairs succeed (200) and the two reserved pairs are
 * refused (422); every other pair returns 409 {@code INVALID_STATE_TRANSITION}.
 * The transition endpoint is Admin-only.
 *
 * <p>Deliberately NOT {@code @Transactional}: notification listeners run
 * {@code BEFORE_COMMIT}, so each probe must commit for its audit/notification
 * side effects to be observable. Probes use fresh candidates/internships so
 * committed rows never collide.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1 — Internship lifecycle matrix (AGENTS.md §4)")
class InternshipLifecycleMatrixTest {

    /** Hand-written allowed pairs on the GENERIC endpoint (reserved pairs excluded). */
    private static final Set<String> ALLOWED = Set.of(
            "APPROVED|IN_PROGRESS",
            "IN_PROGRESS|REPORT_SUBMITTED",
            "REPORT_SUBMITTED|UNDER_VALIDATION",
            "UNDER_VALIDATION|REPORT_SUBMITTED");

    /** Reserved pairs: valid in the §4 chain but refused by the generic endpoint (422). */
    private static final Set<String> RESERVED = Set.of(
            "UNDER_VALIDATION|VALIDATED",
            "VALIDATED|RECEIPT_ISSUED");

    private static final List<InternshipStatus> MATRIX_STATUSES = List.of(
            InternshipStatus.APPROVED,
            InternshipStatus.IN_PROGRESS,
            InternshipStatus.REPORT_SUBMITTED,
            InternshipStatus.UNDER_VALIDATION,
            InternshipStatus.VALIDATED,
            InternshipStatus.RECEIPT_ISSUED,
            InternshipStatus.APPROVED,
            InternshipStatus.IN_PROGRESS,
            InternshipStatus.VALIDATED,
            InternshipStatus.CANCELLED);

    /** A probe row with the intern user id resolved before the session closes. */
    private record InternshipProbe(Internship internship, UUID internUserId) {
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private User supervisorUser;
    private String supervisorToken;
    private User internUser;
    private String internToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = userRepository.saveAndFlush(new User("admin_lx_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        if (adminRole != null) {
            adminUser.getAssignedRoles().add(adminRole);
            adminUser = userRepository.saveAndFlush(adminUser);
        }
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_lx_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElse(null);
        if (supervisorRole != null) {
            supervisorUser.getAssignedRoles().add(supervisorRole);
            supervisorUser = userRepository.saveAndFlush(supervisorUser);
        }
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("intern_lx_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
    }

    // =========================================================================
    // Allowed pairs: succeed + audit + notifications
    // =========================================================================

    @ParameterizedTest(name = "{0} → {1} succeeds with audit + notifications")
    @CsvSource({
            "APPROVED,IN_PROGRESS",
            "IN_PROGRESS,REPORT_SUBMITTED",
            "REPORT_SUBMITTED,UNDER_VALIDATION",
            "UNDER_VALIDATION,REPORT_SUBMITTED"})
    @DisplayName("allowed internship transition succeeds with audit entry and both recipients")
    void allowedTransitionSucceeds(String from, String to) throws Exception {
        InternshipProbe probe = createInternship(InternshipStatus.valueOf(from));

        mockMvc.perform(post("/api/internships/" + probe.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InternshipStatusTransitionRequest(InternshipStatus.valueOf(to), "matrix test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(to));

        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("Internship", probe.internship().getId())
                .stream().map(AuditLog::getAction))
                .as("audit entry for %s → %s", from, to)
                .contains("INTERNSHIP_STATUS_CHANGED");
        assertThat(notificationReached(probe.internship().getId(), supervisorUser.getId()))
                .as("the current supervisor must be notified for %s → %s", from, to).isTrue();
        assertThat(notificationReached(probe.internship().getId(), probe.internUserId()))
                .as("the intern must be notified for %s → %s", from, to).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} is reserved for validation/receipt (422)")
    @CsvSource({
            "UNDER_VALIDATION,VALIDATED",
            "VALIDATED,RECEIPT_ISSUED"})
    @DisplayName("reserved validation steps are rejected by the generic endpoint")
    void reservedTransitionsAreRejected(String from, String to) throws Exception {
        InternshipProbe probe = createInternship(InternshipStatus.valueOf(from));

        mockMvc.perform(post("/api/internships/" + probe.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InternshipStatusTransitionRequest(InternshipStatus.valueOf(to), "must go through validation"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_STATUS_RESERVED"));
    }

    // =========================================================================
    // Every other pair: 409
    // =========================================================================

    @Test
    @DisplayName("all non-allowed internship pairs return 409 INVALID_STATE_TRANSITION")
    void nonAllowedPairsReturn409() throws Exception {
        for (InternshipStatus from : MATRIX_STATUSES) {
            for (InternshipStatus to : MATRIX_STATUSES) {
                if (from == to || ALLOWED.contains(from.name() + "|" + to.name())
                        || RESERVED.contains(from.name() + "|" + to.name())) {
                    continue;
                }
                // S6b: ANY request whose target is reserved is refused with 422
                // before the matrix is even consulted — including jumps like
                // APPROVED → VALIDATED. Those are covered by
                // reservedTransitionsAreRejected; skip them here so this probe
                // asserts exactly the 409 complement.
                if (InternshipLifecycleService.RESERVED_FOR_VALIDATION.contains(to)) {
                    continue;
                }
                InternshipProbe probe = createInternship(from);
                mockMvc.perform(post("/api/internships/" + probe.internship().getId() + "/status-transitions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new InternshipStatusTransitionRequest(to, "must be refused"))))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
            }
        }
    }

    // =========================================================================
    // Capability: the transition endpoint is Admin-only
    // =========================================================================

    @Test
    @DisplayName("a supervisor cannot drive the lifecycle endpoint (403)")
    void supervisorCannotDriveLifecycle() throws Exception {
        InternshipProbe probe = createInternship(InternshipStatus.REPORT_SUBMITTED);

        mockMvc.perform(post("/api/internships/" + probe.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InternshipStatusTransitionRequest(InternshipStatus.UNDER_VALIDATION, "attempt"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an intern cannot drive the lifecycle endpoint (403)")
    void internCannotDriveLifecycle() throws Exception {
        InternshipProbe probe = createInternship(InternshipStatus.IN_PROGRESS);

        mockMvc.perform(post("/api/internships/" + probe.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InternshipStatusTransitionRequest(InternshipStatus.REPORT_SUBMITTED, "attempt"))))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InternshipProbe createInternship(InternshipStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try {
            User user = userRepository.saveAndFlush(new User(
                    "intern_lx2_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
            UUID internUserId = user.getId();
            University uni = universityRepository.saveAndFlush(
                    new University("UNI_LX2_" + suffix, "Lifecycle Uni " + suffix));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest(("LX2" + suffix).getBytes(StandardCharsets.UTF_8)));
            Candidate owner = new Candidate("Lifecycle", "Intern " + suffix, user.getEmail(), cinHash, uni);
            owner.setUser(user);
            owner = candidateRepository.saveAndFlush(owner);

            Internship internship = new Internship(
                    "INT-LX-" + UUID.randomUUID().toString().substring(0, 8), owner,
                    LocalDate.now(), LocalDate.now().plusMonths(2),
                    InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE);
            internship.setStatus(status);
            internship.setSupervisorUser(supervisorUser);
            return new InternshipProbe(internshipRepository.saveAndFlush(internship), internUserId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean notificationReached(UUID internshipId, UUID recipientId) {
        return notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Internship", internshipId)
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .anyMatch(d -> d.getRecipient() != null && recipientId.equals(d.getRecipient().getId()));
    }
}
