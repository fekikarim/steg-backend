package tn.steg.backend.candidate.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.event.CandidateValidatedEvent;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.notification.application.NotificationEventListener;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-fix (b), AGENTS.md §8.1: when a candidate validates their front-office
 * account, the Admin (only) is notified — with a deep link to the candidate
 * dossier and no duplicate on repeated validation (Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Validated-candidate Admin notification (Testcontainers)")
class CandidateValidatedNotificationIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;
    @Autowired private NotificationEventListener notificationEventListener;

    private MockMvc mockMvc;
    private String run;
    private University university;
    private User admin;
    private User supervisor;
    private User candidateUser;
    private String adminToken;
    private String supervisorToken;
    private String candidateToken;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User newUser(String tag, String roleCode) {
        User user = userRepository.saveAndFlush(
                new User(tag + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        user.getAssignedRoles().add(role);
        user = userRepository.saveAndFlush(user);
        return user;
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_" + role));
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        admin = newUser("cvn_admin", "ADMIN");
        supervisor = newUser("cvn_sup", "SUPERVISOR");
        candidateUser = newUser("cvn_cand", "CANDIDATE");
        adminToken = token(admin, "ADMIN");
        supervisorToken = token(supervisor, "SUPERVISOR");
        candidateToken = token(candidateUser, "CANDIDATE");

        university = universityRepository.saveAndFlush(new University("UNI_CVN_" + run, "CVN Uni " + run));
    }

    private String createBody(String email, String nationalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Valid");
        body.put("lastName", "Cand" + run);
        body.put("email", email);
        body.put("universityId", university.getId().toString());
        body.put("nationalId", nationalId);
        return objectMapper.writeValueAsString(body);
    }

    private long adminListTotal() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("page").path("totalElements").asLong();
    }

    @Test
    @DisplayName("self-registration notifies the Admin only, with a deep link to the candidate")
    void validatedCandidateNotifiesAdminOnly() throws Exception {
        String email = ("cvn_" + run + "@steg.tn").toLowerCase();
        MvcResult created = mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody(email, "CIN-CVN-" + run)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());

        // Admin received exactly one notification with the candidate deep link.
        MvcResult listed = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(listed.getResponse().getContentAsString()).path("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.path(0).path("relatedEntityType").asText()).isEqualTo("Candidate");
        assertThat(content.path(0).path("relatedEntityId").asText()).isEqualTo(candidateId.toString());

        // Repo-level: exactly one notification row behind the dedupe key.
        assertThat(notificationRepository.findByDedupeKey("CANDIDATE_VALIDATED:" + candidateId)).isPresent();

        // A Supervisor is never a recipient of this event.
        MvcResult supListed = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(supListed.getResponse().getContentAsString())
                .path("page").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("repeated validation of the same candidate never duplicates the Admin notification")
    void repeatedValidationDoesNotDuplicate() throws Exception {
        String email = ("cvn_dup_" + run + "@steg.tn").toLowerCase();
        MvcResult created = mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody(email, "CIN-CVNDUP-" + run)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());
        assertThat(adminListTotal()).isEqualTo(1);

        // The same business fact re-processed (retry, event replay, double
        // submit — each with its own event id) reuses the existing
        // notification: no second row, no second delivery.
        Candidate stored = candidateRepository.findAll().stream()
                .filter(c -> c.getId().equals(candidateId))
                .findFirst()
                .orElseThrow();
        notificationEventListener.onCandidateValidated(new CandidateValidatedEvent(
                candidateId, candidateUser.getId(), stored.getEmail(),
                stored.getFirstName() + " " + stored.getLastName(), candidateUser.getId()));
        notificationEventListener.onCandidateValidated(new CandidateValidatedEvent(
                candidateId, candidateUser.getId(), stored.getEmail(),
                stored.getFirstName() + " " + stored.getLastName(), candidateUser.getId()));

        assertThat(adminListTotal()).isEqualTo(1);
        assertThat(notificationRepository.findByDedupeKey("CANDIDATE_VALIDATED:" + candidateId)).isPresent();
        // Role fan-out reaches every Admin in the database (shared suite DB),
        // so scope the exactly-once assertion to THIS test's admin: exactly
        // one IN_APP delivery on the single notification row.
        var related = notificationRepository.findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc(
                "Candidate", candidateId);
        assertThat(related).hasSize(1);
        UUID notificationId = related.get(0).getId();
        java.util.function.Supplier<Long> myInAppDeliveries = () ->
                deliveryRepository.findByNotificationId(notificationId).stream()
                        .filter(d -> d.getRecipient() != null && admin.getId().equals(d.getRecipient().getId()))
                        .filter(d -> d.getChannel()
                                == tn.steg.backend.notification.domain.model.NotificationChannel.IN_APP)
                        .count();
        assertThat(myInAppDeliveries.get()).isEqualTo(1);
    }
}
