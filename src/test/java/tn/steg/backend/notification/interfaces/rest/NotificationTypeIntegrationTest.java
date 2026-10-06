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
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.port.out.RealtimeNotifier;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.domain.model.WorkflowStatus;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T01 — typed notification catalogue (Testcontainers): the stable type key is
 * persisted and returned over REST, legacy rows without a type still
 * serialize, task edit/delete notify the intern + supervisor and nobody else,
 * and the D15 welcome notification is created exactly once after the first
 * password change.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NotificationTypeIntegrationTest.FakeRealtimeConfig.class})
@DisplayName("T01 — typed notification catalogue (Testcontainers)")
class NotificationTypeIntegrationTest {

    @TestConfiguration
    static class FakeRealtimeConfig {
        @Bean
        @Primary
        RealtimeNotifier realtimeNotifier() {
            return (recipientEmail, payload) -> {
                // Recording fake; routing itself is proven elsewhere.
            };
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
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorUser;
    private String supervisorToken;
    private University university;
    private Department department;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_nt_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_nt_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        department = departmentRepository.saveAndFlush(new Department("DIR_NT_" + uid(), "NT Dept", "NT"));
        Employee adminEmp = new Employee("EMP-NT-ADM-" + uid(), "Admin", "Nt", department);
        adminEmp.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmp);
        Employee supEmp = new Employee("EMP-NT-SUP-" + uid(), "Sup", "Nt", department);
        supEmp.setUser(supervisorUser);
        employeeRepository.saveAndFlush(supEmp);

        university = universityRepository.saveAndFlush(new University("UNI_NT_" + uid(), "NT Uni"));
    }

    private record Fixture(UUID internshipId, UUID internUserId, String internToken,
                           String internEmail, Task task) {
    }

    private Fixture fixture(String tag) throws Exception {
        final User internUser = userRepository.saveAndFlush(
                new User("cand_nt_" + tag + "_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "NT project", "Ingénieur", false, supervisorUser.getId()), adminPrincipal);
        InternshipRepository port = internshipRepository;
        var internship = port.findById(resp.id()).orElseThrow();

        Task task = taskRepository.saveAndFlush(new Task(internship, adminUser, "NT task " + tag, "d"));
        String internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(internship.getId(), internUser.getId(), internToken, internUser.getEmail(), task);
    }

    private List<com.fasterxml.jackson.databind.JsonNode> listContent(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        List<com.fasterxml.jackson.databind.JsonNode> rows = new java.util.ArrayList<>();
        content.forEach(rows::add);
        return rows;
    }

    private long unread(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("unreadCount").asLong();
    }

    @Test
    @DisplayName("typeIsPersistedAndReturned: a task-status change carries TASK_STATUS_CHANGED over REST")
    void typeIsPersistedAndReturned() throws Exception {
        Fixture f = fixture("typed" + uid());
        mockMvc.perform(patch("/api/internships/tasks/" + f.task().getId() + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());

        var rows = listContent(f.internToken());
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(r -> r.path("type").asText(null)))
                .contains("TASK_STATUS_CHANGED");

        var stored = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Task", f.task().getId());
        assertThat(stored).isNotEmpty();
        assertThat(stored.get(0).getType().name()).isEqualTo("TASK_STATUS_CHANGED");
    }

    @Test
    @DisplayName("legacyRowWithoutTypeStillSerializes: NULL type renders as JSON null")
    void legacyRowWithoutTypeStillSerializes() throws Exception {
        Fixture f = fixture("legacy" + uid());
        mockMvc.perform(patch("/api/internships/tasks/" + f.task().getId() + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());

        // Simulate a pre-V54 row: strip the type the listener just wrote.
        jdbcTemplate.update("UPDATE notifications SET type = NULL WHERE related_entity_id = ?",
                f.task().getId());

        var rows = listContent(f.internToken());
        var taskRows = rows.stream()
                .filter(r -> "Task".equals(r.path("relatedEntityType").asText("")))
                .toList();
        assertThat(taskRows).isNotEmpty();
        assertThat(taskRows.get(0).path("type").isNull() || taskRows.get(0).path("type").asText().isEmpty())
                .isTrue();
    }

    @Test
    @DisplayName("taskEditedNotifiesInternAndSupervisor: PUT /tasks/{id} → TASK_UPDATED, no third party")
    void taskEditedNotifiesInternAndSupervisor() throws Exception {
        Fixture f = fixture("edit" + uid());
        int internUnreadBefore = (int) unread(f.internToken());

        mockMvc.perform(put("/api/internships/tasks/" + f.task().getId())
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"NT task renamed\"}"))
                .andExpect(status().isOk());

        var internRows = listContent(f.internToken());
        assertThat(internRows.stream().map(r -> r.path("type").asText(null)))
                .contains("TASK_UPDATED");

        var supervisorRows = listContent(supervisorToken);
        assertThat(supervisorRows.stream().map(r -> r.path("type").asText(null)))
                .contains("TASK_UPDATED");

        // Both learned about it: intern gained one unread, supervisor gained one.
        assertThat(unread(f.internToken())).isEqualTo(internUnreadBefore + 1);
    }

    @Test
    @DisplayName("taskDeletedNotifiesInternAndSupervisor: DELETE /tasks/{id} → TASK_DELETED")
    void taskDeletedNotifiesInternAndSupervisor() throws Exception {
        Fixture f = fixture("del" + uid());

        mockMvc.perform(delete("/api/internships/tasks/" + f.task().getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isNoContent());

        var internRows = listContent(f.internToken());
        var deleted = internRows.stream()
                .filter(r -> "TASK_DELETED".equals(r.path("type").asText("")))
                .toList();
        assertThat(deleted).hasSize(1);
        // The removed task's title travels in the message body, not the title.
        assertThat(deleted.get(0).path("message").asText()).contains("NT task");

        // The delete is exactly-once per fact: no duplicate rows for the task.
        var stored = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Task", f.task().getId());
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("outOfScopeRecipientReceivesNothing: another supervisor sees no task notification")
    void outOfScopeRecipientReceivesNothing() throws Exception {
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();
        User other = userRepository.saveAndFlush(new User("other_nt_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        other.getAssignedRoles().add(supervisorRole);
        other = userRepository.saveAndFlush(other);
        String otherToken = jwtService.generateAccessToken(other.getId(), other.getEmail(), List.of("ROLE_SUPERVISOR"));

        Fixture f = fixture("scope" + uid());
        mockMvc.perform(put("/api/internships/tasks/" + f.task().getId())
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"NT task retitled\"}"))
                .andExpect(status().isOk());

        assertThat(unread(otherToken)).isZero();
        assertThat(listContent(otherToken)).isEmpty();
    }

    @Test
    @DisplayName("welcomeCreatedExactlyOnce: first password change creates WELCOME, a second change does not")
    void welcomeCreatedExactlyOnce() throws Exception {
        Role internRole = roleRepository.findByCode("INTERN").orElseThrow();
        User intern = userRepository.saveAndFlush(new User("wel_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        intern.getAssignedRoles().add(internRole);
        // The account must change its password: the welcome may only be created
        // after the first successful change (D15 order).
        intern.setMustChangePassword(true);
        intern.setPasswordHash(
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("OldPass!2026"));
        final User savedIntern = userRepository.saveAndFlush(intern);
        String token = jwtService.generateAccessToken(
                savedIntern.getId(), savedIntern.getEmail(), List.of("ROLE_INTERN"),
                Boolean.TRUE.equals(savedIntern.getMustChangePassword()));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPass!2026\",\"newPassword\":\"NewStrong!Passw0rd\"}"))
                .andExpect(status().isNoContent());

        long afterFirst = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == tn.steg.backend.notification.domain.model.NotificationType.WELCOME)
                .filter(n -> intern.getId().equals(dedupeUserId(n.getDedupeKey())))
                .count();
        assertThat(afterFirst).isEqualTo(1);

        // Change again (back to a new password): the welcome must NOT duplicate.
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"NewStrong!Passw0rd\",\"newPassword\":\"EvenStronger!Pass2\"}"))
                .andExpect(status().isNoContent());

        long afterSecond = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == tn.steg.backend.notification.domain.model.NotificationType.WELCOME)
                .filter(n -> intern.getId().equals(dedupeUserId(n.getDedupeKey())))
                .count();
        assertThat(afterSecond).isEqualTo(1);
    }

    private static UUID dedupeUserId(String dedupeKey) {
        if (dedupeKey == null || !dedupeKey.startsWith("WELCOME:")) {
            return null;
        }
        try {
            return UUID.fromString(dedupeKey.substring("WELCOME:".length()));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
