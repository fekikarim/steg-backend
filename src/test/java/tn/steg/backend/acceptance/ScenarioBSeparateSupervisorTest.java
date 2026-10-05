package tn.steg.backend.acceptance;

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
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.application.dto.BulkTaskAction;
import tn.steg.backend.companion.application.dto.BulkTaskMutation;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12a Scenario B — a separate Supervisor is scoped to his own candidates
 * (AGENTS.md §12), over HTTP against a real Postgres.
 *
 * <p>Chain: Admin creates Supervisor S (working login) → another application
 * is approved with S selected → S sees only his candidates/tasks and gets
 * their task notifications while the Admin gets none → S is 403 on
 * Admin-only endpoints and 404 on another supervisor's data → Admin reassigns
 * the candidate to himself → scope, notifications and audit follow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S12a — Scenario B: separate supervisor is scoped, Admin stays global")
class ScenarioBSeparateSupervisorTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private University university;
    private Department department;
    private String run;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminUser = userRepository.saveAndFlush(new User("admin_b_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        university = universityRepository.saveAndFlush(new University("UNI_B_" + run, "Scenario B Uni"));
        department = departmentRepository.saveAndFlush(new Department("DIR_B_" + run, "Scenario B Dept", "SB"));
    }

    private record Student(UUID candidateId, UUID applicationId, UUID internshipId, String email,
                           UUID candidateUserId, String internToken) {
    }

    private record SupervisorAccount(User user, String token, String tempPassword) {
    }

    @Test
    @DisplayName("Scenario B: separate supervisor scope, notifications, 403/404 guards, reassignment to Admin")
    void separateSupervisorIsScopedAndReassignable() throws Exception {
        // 1. Admin creates Supervisor S — a working back-office login.
        SupervisorAccount s = createSupervisor("sup_s_" + run + "@steg.tn");
        SupervisorAccount other = createSupervisor("sup_o_" + run + "@steg.tn");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", s.user().getEmail(), "password", s.tempPassword()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // 2. Two candidates register + submit; one is approved with S selected,
        // the other with the other supervisor selected.
        Student owned = registerSubmitAndApprove("b_owned_" + run + "@steg.tn", "CIN-B1-" + run, s.user().getId());
        Student foreign = registerSubmitAndApprove("b_foreign_" + run + "@steg.tn", "CIN-B2-" + run,
                other.user().getId());

        // S sees ONLY his own candidate in the scoped list …
        MvcResult listed = mockMvc.perform(get("/api/candidates/manage?size=100")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk())
                .andReturn();
        String listPayload = listed.getResponse().getContentAsString();
        assertThat(listPayload).contains(owned.email());
        assertThat(listPayload).doesNotContain(foreign.email());

        // … and only his own tasks …
        MvcResult tasks = mockMvc.perform(get("/api/internships/tasks?size=100")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(tasks.getResponse().getContentAsString()).doesNotContain(foreign.internshipId().toString());

        // … reading the other supervisor's candidate is 404 (no existence leak).
        mockMvc.perform(get("/api/candidates/" + foreign.candidateId() + "/overview")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isNotFound());

        // 3. A task completed by S's intern notifies S — and NOT the Admin.
        String taskId = createTask(adminToken, owned.internshipId(), "S task 1");
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status")
                        .param("status", "COMPLETED")
                        .header("Authorization", "Bearer " + owned.internToken()))
                .andExpect(status().isOk());
        assertThat(deliveriesFor(s.user().getId(), "Task", UUID.fromString(taskId))).isNotEmpty();
        assertThat(deliveriesFor(adminUser.getId(), "Task", UUID.fromString(taskId))).isEmpty();

        // The Admin still sees everything in the global pages …
        MvcResult globalTasks = mockMvc.perform(get("/api/internships/tasks?size=100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(globalTasks.getResponse().getContentAsString()).contains(owned.internshipId().toString());
        // … and the Admin dashboard stays global while S's stays scoped.
        MvcResult supSummary = mockMvc.perform(get("/api/reports/supervisor-summary")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(supSummary.getResponse().getContentAsString())
                .path("myCandidates").asLong()).isEqualTo(1);

        // 4. S cannot reach Admin-only endpoints (403), even though the UI hides them too.
        mockMvc.perform(get("/api/supervisors/manage")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internship-validation/queue")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internship-validation/" + owned.internshipId() + "/receipt")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internships/" + owned.internshipId() + "/certificates")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isForbidden());

        // … nor approve an application.
        Student pending = registerAndSubmit("b_pending_" + run + "@steg.tn", "CIN-B3-" + run);
        mockMvc.perform(post("/api/applications/" + pending.applicationId() + "/approve")
                        .header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isForbidden());

        // 5. Admin reassigns the candidate from S to himself.
        mockMvc.perform(put("/api/supervisors/" + s.user().getId() + "/reassign/" + owned.internshipId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newSupervisorUserId", adminUser.getId().toString()))))
                .andExpect(status().isOk());

        // Scope follows: S no longer sees the candidate, the Admin own-scope does.
        MvcResult relisted = mockMvc.perform(get("/api/candidates/manage?size=100")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(relisted.getResponse().getContentAsString()).doesNotContain(owned.email());

        // Notifications follow: the next completion reaches the Admin, no longer S.
        String taskId2 = createTask(adminToken, owned.internshipId(), "S task 2");
        mockMvc.perform(patch("/api/internships/tasks/" + taskId2 + "/status")
                        .param("status", "COMPLETED")
                        .header("Authorization", "Bearer " + owned.internToken()))
                .andExpect(status().isOk());
        assertThat(deliveriesFor(adminUser.getId(), "Task", UUID.fromString(taskId2))).isNotEmpty();
        assertThat(deliveriesFor(s.user().getId(), "Task", UUID.fromString(taskId2))).isEmpty();

        // Audit follows: the reassignment records old → new supervisor.
        assertThat(auditLogRepository.findByAction(
                        "SUPERVISOR_REASSIGNED", org.springframework.data.domain.Pageable.unpaged()))
                .isNotEmpty();
        String payload = auditLogRepository.findByAction(
                        "SUPERVISOR_REASSIGNED", org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(l -> owned.internshipId().equals(l.getEntityId()))
                .map(l -> String.valueOf(l.getNewValues()))
                .findFirst().orElse("");
        assertThat(payload).contains(adminUser.getId().toString());
    }

    // ------------------------------------------------------------------
    // Helpers (all transitions go through HTTP, never repositories)
    // ------------------------------------------------------------------

    private SupervisorAccount createSupervisor(String email) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/supervisors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String tempPassword = body.get("temporaryPassword").asText();
        User user = userRepository.findByEmail(email).orElseThrow();
        String token = jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_SUPERVISOR"));
        return new SupervisorAccount(user, token, tempPassword);
    }

    private Student registerSubmitAndApprove(String email, String cin, UUID supervisorUserId) throws Exception {
        Student pending = registerAndSubmit(email, cin);
        MvcResult approved = mockMvc.perform(post("/api/applications/" + pending.applicationId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(supervisorUserId, department.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisorUserId").value(supervisorUserId.toString()))
                .andReturn();
        UUID internshipId = UUID.fromString(objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("internship").get("id").asText());
        User provisioned = userRepository.findByEmail(email).orElseThrow();
        String internToken = jwtService.generateAccessToken(
                provisioned.getId(), provisioned.getEmail(), List.of("ROLE_INTERN"));
        return new Student(pending.candidateId(), pending.applicationId(), internshipId, email,
                provisioned.getId(), internToken);
    }

    private Student registerAndSubmit(String email, String cin) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "SmokePass!2026",
                                "firstName", "S12b",
                                "lastName", "Student " + run))))
                .andExpect(status().isCreated())
                .andReturn();
        String candidateToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("accessToken").asText();

        MvcResult profile = mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "S12b",
                                "lastName", "Student " + run,
                                "email", email,
                                "universityId", university.getId().toString(),
                                "nationalId", cin))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper.readTree(profile.getResponse().getContentAsString())
                .get("id").asText());

        MvcResult created = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "desiredStartDate", LocalDate.of(2026, 1, 5).toString(),
                                "desiredEndDate", LocalDate.of(2026, 6, 5).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID appId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
        mockMvc.perform(post("/api/applications/" + appId + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk());

        User candidateUser = userRepository.findByEmail(email).orElseThrow();
        String internToken = jwtService.generateAccessToken(
                candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_INTERN"));
        return new Student(candidateId, appId, null, email, candidateUser.getId(), internToken);
    }

    private String createTask(String token, UUID internshipId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Idempotency-Key", "s12b-" + run + "-" + title.hashCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new BulkTaskMutation(BulkTaskAction.CREATE, internshipId, null,
                                        new TaskRequest(title, "desc", null, null, null))))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("tasks").path(0).path("id").asText();
    }

    private List<tn.steg.backend.notification.domain.model.NotificationDelivery> deliveriesFor(
            UUID recipientId, String entityType, UUID entityId) {
        return notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc(entityType, entityId).stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .filter(d -> d.getRecipient() != null && recipientId.equals(d.getRecipient().getId()))
                .toList();
    }
}
