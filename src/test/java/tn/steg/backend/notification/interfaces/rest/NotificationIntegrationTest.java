package tn.steg.backend.notification.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.dto.JournalEntryRequest;
import tn.steg.backend.companion.application.dto.TaskRequest;
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
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.notification.domain.model.NotificationChannel;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationDeliveryStatus;
import tn.steg.backend.notification.domain.repository.NotificationDeliveryRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A10 tests: event → notification → delivery fan-out, channel
 * selection, retry-on-failure, and per-user authorization.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code @TransactionalEventListener}
 * (AFTER_COMMIT) only fires on real commits, exactly like production.
 * Throwaway Testcontainers DB + unique fixtures per test keep isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "steg.notifications.mail.enabled=true")
@DisplayName("Notification Module Integration Tests (A10)")
class NotificationIntegrationTest {

    /** Fake SMTP: records sends, fails programmable recipients. */
    static class FakeEmailSender implements EmailSender {
        record SentMail(String to, String subject, String body) {}

        final List<SentMail> sent = new CopyOnWriteArrayList<>();
        final Set<String> failingRecipients = ConcurrentHashMap.newKeySet();

        @Override
        public void send(String to, String subject, String body) {
            if (failingRecipients.contains(to)) {
                throw new RuntimeException("SMTP 550 mailbox unavailable for " + to);
            }
            sent.add(new SentMail(to, subject, body));
        }
    }

    @TestConfiguration
    static class FakeMailConfiguration {
        @Bean
        @Primary
        EmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

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
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private tn.steg.backend.companion.application.CompanionService companionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private FakeEmailSender fakeMail;


    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        fakeMail.sent.clear();
        fakeMail.failingRecipients.clear();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User newUser(String email) {
        return userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
    }

    private String token(User user, String... roles) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(roles));
    }

    private Candidate newCandidate(String tag, User user, University uni) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Notif", tag, user.getEmail(), hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(tag);
        return candidateRepository.saveAndFlush(candidate);
    }

    private InternshipApplication newSubmittedApplication(String tag, Candidate candidate) {
        InternshipApplication app = new InternshipApplication("APP-N-" + tag, candidate, ApplicationStatus.SUBMITTED);
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);
        return app;
    }

    private void acceptApplication(InternshipApplication app, UserPrincipal hrPrincipal) {
        workflowService.transitionApplication(app.getId(),
                new WorkflowTransitionRequest("UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "reviewing"),
                hrPrincipal);
        workflowService.transitionApplication(app.getId(),
                new WorkflowTransitionRequest("FINAL_DECISION", WorkflowActionType.APPROVAL,
                        ApprovalDecision.APPROVED, "accepted"),
                hrPrincipal);
    }

    private List<NotificationDelivery> deliveriesFor(UUID notificationId) {
        return deliveryRepository.findByNotificationId(notificationId);
    }

    private UUID acceptAndGetNotificationId(User candidateUser, User hrUser, String tag) throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_N_" + tag, "Notif Uni " + tag));
        Candidate candidate = newCandidate(tag, candidateUser, uni);
        InternshipApplication app = newSubmittedApplication(tag, candidate);
        acceptApplication(app, new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR")));

        MvcResult list = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(candidateUser, "ROLE_CANDIDATE")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(list.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isGreaterThanOrEqualTo(1);
        return UUID.fromString(content.get(0).get("id").asText());
    }

    // -------------------------------------------------------------------------
    // Fan-out
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Application accept fans out HIGH notification with IN_APP + EMAIL deliveries")
    void applicationAcceptanceFanOut() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_n_" + tag + "@steg.com");
        User candidateUser = newUser("cand_n_" + tag + "@steg.com");

        UUID notificationId = acceptAndGetNotificationId(candidateUser, hrUser, tag);

        List<NotificationDelivery> deliveries = deliveriesFor(notificationId);
        assertThat(deliveries).extracting(d -> d.getChannel())
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
        assertThat(deliveries).allMatch(d -> d.getStatus() == NotificationDeliveryStatus.SENT);
        assertThat(fakeMail.sent).anyMatch(m -> m.to().equals(candidateUser.getEmail()));
    }

    @Test
    @DisplayName("NORMAL events create IN_APP only (no EMAIL row, no SMTP call)")
    void normalPriorityCreatesInAppOnly() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_t_" + tag + "@steg.com");
        User internUser = newUser("intern_t_" + tag + "@steg.com");
        User supervisorUser = newUser("sup_t_" + tag + "@steg.com");

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_N_" + tag, "Notif Dept", "N"));
        Employee supervisorEmployee = new Employee("EMP-N-" + tag, "Notif", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_T_" + tag, "Task Uni"));
        Candidate candidate = newCandidate("TASK" + tag, internUser, uni);
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(3), LocalDate.now().plusDays(60),
                "Notif project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(60), "task test"), hrPrincipal);

        // Supervisor assigns a task to the intern → NORMAL event
        int mailsBeforeTask = fakeMail.sent.size();
        mockMvc.perform(post("/api/internships/" + internship.getId() + "/tasks")
                        .header("Authorization", "Bearer " + token(supervisorUser, "ROLE_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskRequest(
                                "Prepare report", "Draft the weekly report", internUser.getId(), null, null))))
                .andExpect(status().isCreated());

        MvcResult list = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(internUser, "ROLE_INTERN")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(list.getResponse().getContentAsString()).get("content");
        JsonNode taskNotif = null;
        for (JsonNode n : content) {
            if (n.get("title").asText().equals("New task assigned")) {
                taskNotif = n;
            }
        }
        assertThat(taskNotif).isNotNull();
        List<NotificationDelivery> deliveries = deliveriesFor(UUID.fromString(taskNotif.get("id").asText()));
        assertThat(deliveries).extracting(d -> d.getChannel())
                .containsExactly(NotificationChannel.IN_APP);
        // NORMAL priority triggers no new SMTP traffic (setup HIGH mails excluded via delta)
        assertThat(fakeMail.sent.size()).isEqualTo(mailsBeforeTask);
    }

    @Test
    @DisplayName("Chat message notifies the other member (LOW, IN_APP, sender excluded)")
    void privateMessageNotifiesOtherMember() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_m_" + tag + "@steg.com");
        User internUser = newUser("intern_m_" + tag + "@steg.com");
        User supervisorUser = newUser("sup_m_" + tag + "@steg.com");

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_M_" + tag, "Msg Dept", "M"));
        Employee supervisorEmployee = new Employee("EMP-M-" + tag, "Msg", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_M_" + tag, "Msg Uni"));
        Candidate candidate = newCandidate("MSG" + tag, internUser, uni);
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(3), LocalDate.now().plusDays(60),
                "Msg project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(60), "msg test"), hrPrincipal);

        MvcResult convs = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + token(internUser, "ROLE_INTERN")))
                .andExpect(status().isOk())
                .andReturn();
        UUID convId = UUID.fromString(objectMapper.readTree(convs.getResponse().getContentAsString()).get(0).get("id").asText());

        mockMvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + token(internUser, "ROLE_INTERN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello supervisor\"}"))
                .andExpect(status().isCreated());

        // Supervisor notified; sender is not notified about their own message
        MvcResult supList = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(supervisorUser, "ROLE_SUPERVISOR")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(supList.getResponse().getContentAsString())
                .get("content").get(0).get("title").asText()).isEqualTo("New message");

        MvcResult internList = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(internUser, "ROLE_INTERN")))
                .andExpect(status().isOk())
                .andReturn();
        // Sender is never notified about their own message (other setup
        // notifications such as "Internship assigned" may still be present)
        for (JsonNode n : objectMapper.readTree(internList.getResponse().getContentAsString()).get("content")) {
            assertThat(n.get("title").asText()).isNotEqualTo("New message");
        }
    }

    // -------------------------------------------------------------------------
    // Retry
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EMAIL failure is recorded then recovered by the retry sweep; poison stays FAILED")
    void retryRecoversAndDeadLetters() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_r_" + tag + "@steg.com");
        User candidateUser = newUser("cand_r_" + tag + "@steg.com");
        fakeMail.failingRecipients.add(candidateUser.getEmail());

        UUID notificationId = acceptAndGetNotificationId(candidateUser, hrUser, tag);

        NotificationDelivery email = deliveriesFor(notificationId).stream()
                .filter(d -> d.getChannel() == NotificationChannel.EMAIL)
                .findFirst().orElseThrow();
        assertThat(email.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(email.getAttemptCount()).isEqualTo(1);
        assertThat(email.getFailureReason()).contains("550");
        assertThat(email.getNextRetryAt()).isAfter(Instant.now().minusSeconds(5));

        // Backoff elapsed + SMTP healthy again → sweep recovers
        email.setNextRetryAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(email);
        fakeMail.failingRecipients.clear();
        assertThat(notificationService.retryFailedDeliveries()).isGreaterThanOrEqualTo(1);

        NotificationDelivery recovered = deliveriesFor(notificationId).stream()
                .filter(d -> d.getChannel() == NotificationChannel.EMAIL)
                .findFirst().orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(fakeMail.sent).anyMatch(m -> m.to().equals(candidateUser.getEmail()));

        // Permanent poison on a FRESH failing notification exhausts attempts and
        // stays FAILED (dead-letter, visible) — the sweep stops picking it up.
        String tag2 = suffix();
        User poisonUser = newUser("poison_r_" + tag2 + "@steg.com");
        fakeMail.failingRecipients.add(poisonUser.getEmail());
        UUID poisonId = acceptAndGetNotificationId(poisonUser, hrUser, tag2);
        for (int i = 0; i < 6; i++) {
            for (NotificationDelivery row : deliveriesFor(poisonId)) {
                if (row.getChannel() == NotificationChannel.EMAIL) {
                    row.setNextRetryAt(Instant.now().minusSeconds(1));
                    deliveryRepository.save(row);
                }
            }
            notificationService.retryFailedDeliveries();
        }
        NotificationDelivery dead = deliveriesFor(poisonId).stream()
                .filter(d -> d.getChannel() == NotificationChannel.EMAIL)
                .findFirst().orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(dead.getAttemptCount()).isEqualTo(5);
        assertThat(dead.getFailureReason()).isNotBlank();
        // Exhausted rows are no longer picked up, even with backoff elapsed
        assertThat(notificationService.retryFailedDeliveries()).isEqualTo(0);
        fakeMail.failingRecipients.clear();
    }

    // -------------------------------------------------------------------------
    // Authorization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Users only ever see and read their own notifications")
    void usersOnlySeeOwnNotifications() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_a_" + tag + "@steg.com");
        User candidateUser = newUser("cand_a_" + tag + "@steg.com");
        User strangerUser = newUser("stranger_a_" + tag + "@steg.com");

        UUID notificationId = acceptAndGetNotificationId(candidateUser, hrUser, tag);

        // Stranger's list is empty; reading another user's id yields 404
        MvcResult strangerList = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(strangerUser, "ROLE_CANDIDATE")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(strangerList.getResponse().getContentAsString())
                .get("content").size()).isEqualTo(0);

        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + token(strangerUser, "ROLE_CANDIDATE")))
                .andExpect(status().isNotFound());

        // Owner reads: read flag + unread filter + count behave
        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + token(candidateUser, "ROLE_CANDIDATE")))
                .andExpect(status().isOk());

        MvcResult unread = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(candidateUser, "ROLE_CANDIDATE"))
                        .param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(unread.getResponse().getContentAsString())
                .get("content").size()).isEqualTo(0);

        MvcResult count = mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + token(candidateUser, "ROLE_CANDIDATE")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(count.getResponse().getContentAsString())
                .get("unreadCount").asLong()).isEqualTo(0L);

        MvcResult readAll = mockMvc.perform(post("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + token(candidateUser, "ROLE_CANDIDATE")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(readAll.getResponse().getContentAsString())
                .get("markedRead").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Journal validation notifies the intern author")
    void journalValidationNotifiesIntern() throws Exception {
        String tag = suffix();
        User hrUser = newUser("hr_j_" + tag + "@steg.com");
        User internUser = newUser("intern_j_" + tag + "@steg.com");
        User supervisorUser = newUser("sup_j_" + tag + "@steg.com");

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_J_" + tag, "J Dept", "J"));
        Employee supervisorEmployee = new Employee("EMP-J-" + tag, "J", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_J_" + tag, "J Uni"));
        Candidate candidate = newCandidate("JRNL" + tag, internUser, uni);
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(3), LocalDate.now().plusDays(60),
                "Journal project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(60), "journal test"), hrPrincipal);

        UserPrincipal internPrincipal = new UserPrincipal(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        var entry = companionService.createJournalEntry(internship.getId(),
                new JournalEntryRequest("Day 1", "Did things", LocalDate.now().minusDays(1)), internPrincipal);
        companionService.submitJournalEntry(entry.id(), internPrincipal);
        companionService.validateJournalEntry(entry.id(), new tn.steg.backend.companion.application.dto.ValidationRequest("well done"),
                new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR")));

        MvcResult list = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(internUser, "ROLE_INTERN")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(list.getResponse().getContentAsString()).get("content");
        boolean found = false;
        for (JsonNode n : content) {
            if (n.get("title").asText().equals("Journal entry validated")) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }
}
