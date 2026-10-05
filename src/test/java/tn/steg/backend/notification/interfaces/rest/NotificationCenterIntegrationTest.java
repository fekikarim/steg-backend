package tn.steg.backend.notification.interfaces.rest;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.application.port.out.RealtimeNotifier;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S9 notification center (AGENTS.md §8.1, Testcontainers): per-recipient
 * read independence, STOMP push routing to listed recipients only,
 * scope-correct recipients per event type, and recomputation after
 * reassignment. No @Transactional — notifications commit for real.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S9 — notification center (Testcontainers)")
class NotificationCenterIntegrationTest {

    /** Recording STOMP fake: proves routing, replaces the broker. */
    static final List<String> pushedEmails = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeRealtimeConfig {
        @Bean
        @Primary
        RealtimeNotifier realtimeNotifier() {
            return (recipientEmail, payload) -> pushedEmails.add(recipientEmail);
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorAUser;
    private String supervisorAToken;
    private User supervisorBUser;
    private University university;
    private Department department;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        pushedEmails.clear();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_nc_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorAUser = userRepository.saveAndFlush(new User("supA_nc_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorAUser.getAssignedRoles().add(supervisorRole);
        supervisorAUser = userRepository.saveAndFlush(supervisorAUser);
        supervisorAToken = jwtService.generateAccessToken(
                supervisorAUser.getId(), supervisorAUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        supervisorBUser = userRepository.saveAndFlush(new User("supB_nc_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorBUser.getAssignedRoles().add(supervisorRole);
        supervisorBUser = userRepository.saveAndFlush(supervisorBUser);

        department = departmentRepository.saveAndFlush(new Department("DIR_NC_" + uid(), "NC Dept", "NC"));
        Employee empA = new Employee("EMP-NC-A-" + uid(), "Nc", "A", department);
        empA.setUser(supervisorAUser);
        employeeRepository.saveAndFlush(empA);
        Employee adminEmployee = new Employee("EMP-NC-ADM-" + uid(), "Admin", "Nc", department);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        university = universityRepository.saveAndFlush(new University("UNI_NC_" + uid(), "NC Uni"));
    }

    private record Fixture(Internship internship, UUID internUserId, String internEmail,
                           String internToken, Task task) {
    }

    private Fixture fixture(String tag, UUID supervisorUserId) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_nc_" + tag + "_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "NC project", "Ingénieur", false, supervisorUserId), adminPrincipal);
        tn.steg.backend.internship.domain.repository.InternshipRepository port = internshipRepository;
        Internship internship = port.findById(resp.id()).orElseThrow();

        Task task = taskRepository.saveAndFlush(new Task(internship, adminUser, "NC task " + tag, "d"));
        String internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(internship, internUser.getId(), internUser.getEmail(), internToken, task);
    }

    private long unread(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("unreadCount").asLong();
    }

    private void completeTask(Fixture f, UUID taskId) throws Exception {
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("two recipients of one event read independently; cross-read is 404")
    void twoRecipientsReadIndependently() throws Exception {
        Fixture f = fixture("indep" + uid(), supervisorAUser.getId());

        // Lifecycle transition notifies the supervisor AND the intern.
        mockMvc.perform(post("/api/internships/" + f.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"t\"}"))
                .andExpect(status().isOk());

        assertThat(unread(supervisorAToken)).isEqualTo(1);
        assertThat(unread(f.internToken())).isEqualTo(1);

        // Supervisor reads his delivery: intern still unread.
        MvcResult listed = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID notificationId = UUID.fromString(objectMapper.readTree(
                listed.getResponse().getContentAsString()).path("content").path(0).path("id").asText());
        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());
        assertThat(unread(supervisorAToken)).isZero();
        assertThat(unread(f.internToken())).isEqualTo(1);

    }

    @Test
    @DisplayName("STOMP pushes reach exactly the listed recipients")
    void stompPushReachesOnlyRecipients() throws Exception {
        Fixture f = fixture("push" + uid(), supervisorAUser.getId());

        mockMvc.perform(post("/api/internships/" + f.internship().getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"t\"}"))
                .andExpect(status().isOk());

        assertThat(pushedEmails).containsExactlyInAnyOrder(
                supervisorAUser.getEmail(), f.internEmail());
    }

    @Test
    @DisplayName("task events follow reassignment: old supervisor stops, new starts")
    void taskEventsFollowReassignment() throws Exception {
        Fixture f = fixture("reas" + uid(), supervisorAUser.getId());

        completeTask(f, f.task().getId());
        long countABefore = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Task", f.task().getId())
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .filter(d -> d.getRecipient() != null && supervisorAUser.getId().equals(d.getRecipient().getId()))
                .count();
        assertThat(countABefore).isEqualTo(1);

        // Reassign A → B.
        mockMvc.perform(put("/api/supervisors/" + supervisorAUser.getId()
                                + "/reassign/" + f.internship().getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newSupervisorUserId\":\"" + supervisorBUser.getId()
                                + "\",\"reason\":\"workload\"}"))
                .andExpect(status().isOk());

        Task second = taskRepository.saveAndFlush(new Task(
                ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                        .findById(f.internship().getId()).orElseThrow(),
                adminUser, "NC task two", "d"));
        completeTask(f, second.getId());

        long countAAfter = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Task", second.getId())
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .filter(d -> d.getRecipient() != null && supervisorAUser.getId().equals(d.getRecipient().getId()))
                .count();
        long countBAfter = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Task", second.getId())
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .filter(d -> d.getRecipient() != null && supervisorBUser.getId().equals(d.getRecipient().getId()))
                .count();
        assertThat(countAAfter).isZero();
        assertThat(countBAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("calendar-day date filter resolves in the application time zone (Africa/Tunis)")
    void tunisCalendarDayBounds() throws Exception {
        Fixture f = fixture("tz" + uid(), supervisorAUser.getId());
        completeTask(f, f.task().getId());

        java.time.ZoneId tunis = java.time.ZoneId.of("Africa/Tunis");
        java.time.LocalDate tunisToday = java.time.LocalDate.now(tunis);
        // 00:10 Tunis time = 23:10 UTC the previous day.
        java.time.Instant tenPastMidnightTunis =
                tunisToday.atTime(0, 10).atZone(tunis).toInstant();
        jdbcTemplate.update(
                "UPDATE notification_deliveries SET created_at = ?, updated_at = ?"
                        + " WHERE recipient_id = ?",
                java.sql.Timestamp.from(tenPastMidnightTunis),
                java.sql.Timestamp.from(tenPastMidnightTunis), supervisorAUser.getId());

        MvcResult inWindow = mockMvc.perform(get("/api/notifications")
                        .param("fromDate", tunisToday.toString())
                        .param("toDate", tunisToday.toString())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(inWindow.getResponse().getContentAsString())
                .path("page").path("totalElements").asLong()).isEqualTo(1);

        MvcResult outOfWindow = mockMvc.perform(get("/api/notifications")
                        .param("toDate", tunisToday.minusDays(1).toString())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(outOfWindow.getResponse().getContentAsString())
                .path("page").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("out-of-scope users see nothing: no list rows, zero unread, 404 on foreign read")
    void outOfScopeSeesNothing() throws Exception {
        Fixture f = fixture("scope" + uid(), supervisorAUser.getId());
        completeTask(f, f.task().getId());

        String supervisorBToken = jwtService.generateAccessToken(
                supervisorBUser.getId(), supervisorBUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        assertThat(unread(supervisorBToken)).isZero();
        MvcResult listed = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(listed.getResponse().getContentAsString())
                .path("content").size()).isZero();

        // B tries to mark A's notification read → 404 (no delivery row for B).
        MvcResult aListed = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID foreignId = UUID.fromString(objectMapper.readTree(
                aListed.getResponse().getContentAsString()).path("content").path(0).path("id").asText());
        mockMvc.perform(post("/api/notifications/" + foreignId + "/read")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());
    }
}
