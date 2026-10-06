package tn.steg.backend.companion.interfaces.rest;

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
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.companion.infrastructure.scheduler.TaskVisibilityScheduler;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
 * T04 supervisor task management — scheduling (D8) + review (Testcontainers).
 *
 * <p>Scheduled tasks hide from the student until their moment passes, stay
 * visible to staff, are validated against the internship period, notify the
 * student exactly once on appearance, and stay out of the journal 75 %
 * denominator. Review (approve/deny) stays scoped with a mandatory reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T04 — Task scheduling + review (Testcontainers)")
class TaskSchedulingIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskVisibilityScheduler visibilityScheduler;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String run;
    private User adminUser;
    private User supervisorAUser;
    private User supervisorBUser;
    private UserPrincipal adminPrincipal;
    private String adminToken;
    private String supervisorAToken;
    private String supervisorBToken;
    private University university;
    private Fixture studentA;
    private Fixture studentB;

    private record Fixture(UUID internshipId, User internUser, String internToken) {
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        adminUser = userRepository.saveAndFlush(new User("adm_ts4_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminToken = jwtService.generateAccessToken(
                adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorAUser = supervisedUser("supA_ts4_" + run);
        supervisorAToken = jwtService.generateAccessToken(
                supervisorAUser.getId(), supervisorAUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        supervisorBUser = supervisedUser("supB_ts4_" + run);
        supervisorBToken = jwtService.generateAccessToken(
                supervisorBUser.getId(), supervisorBUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        university = universityRepository.saveAndFlush(new University("UNI_TS4_" + run, "TS4 Uni"));

        // Live period (spans now): scheduling tests need future moments
        // inside the period AND past moments for immediacy.
        studentA = fixture("FA" + run, supervisorAUser.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(80));
        studentB = fixture("FB" + run, supervisorBUser.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(80));
    }

    private User supervisedUser(String email) {
        User user = userRepository.saveAndFlush(new User(email + "@steg.tn", "hash", UserStatus.ACTIVE));
        user.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        return userRepository.saveAndFlush(user);
    }

    private Fixture fixture(String tag, UUID supervisorUserId, LocalDate start, LocalDate end)
            throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_ts4_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), start, end, "TS4 project " + tag, "Technicien", false, supervisorUserId),
                adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(resp.id()).orElseThrow();
        String token = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(internship.getId(), internUser, token);
    }

    private Task reloadTask(UUID id) {
        return ((tn.steg.backend.companion.domain.repository.TaskRepository) taskRepository)
                .findById(id).orElseThrow();
    }

    private JsonNode createTask(String token, UUID internshipId, String title, String visibleFromIso)
            throws Exception {
        String body = "{\"title\":\"" + title + "\",\"description\":\"d\""
                + (visibleFromIso == null ? "" : ",\"visibleFrom\":\"" + visibleFromIso + "\"") + "}";
        MvcResult result = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<JsonNode> internTasks(Fixture f) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/internships/" + f.internshipId() + "/tasks")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        List<JsonNode> rows = new java.util.ArrayList<>();
        content.forEach(rows::add);
        return rows;
    }

    private List<JsonNode> internNotifications(Fixture f) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        List<JsonNode> rows = new java.util.ArrayList<>();
        content.forEach(rows::add);
        return rows;
    }

    private long scheduledVisibleCount(Fixture f) throws Exception {
        return internNotifications(f).stream()
                .filter(n -> "SCHEDULED_TASK_VISIBLE".equals(n.path("type").asText(null)))
                .count();
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scheduledTaskHiddenFromStudentUntilVisible: list, single read and status write are 404; staff sees it")
    void scheduledTaskHiddenFromStudentUntilVisible() throws Exception {
        String future = Instant.now().plusSeconds(2 * 24 * 3600).toString();
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "Future task", future);
        UUID taskId = UUID.fromString(created.path("id").asText());
        assertThat(created.path("visibleFrom").isMissingNode()).isFalse();

        // Intern: absent from the list, single read 404, status write 404.
        assertThat(internTasks(studentA)).isEmpty();
        mockMvc.perform(get("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status?status=IN_PROGRESS")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isNotFound());

        // Supervisor + admin: full view with the scheduled instant exposed.
        mockMvc.perform(get("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleFrom").exists());
        List<JsonNode> staffRows = new java.util.ArrayList<>();
        MvcResult staffList = mockMvc.perform(get("/api/internships/" + studentA.internshipId() + "/tasks")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        objectMapper.readTree(staffList.getResponse().getContentAsString())
                .path("content").forEach(staffRows::add);
        assertThat(staffRows).hasSize(1);
    }

    @Test
    @DisplayName("pastScheduleAndDefaultAreImmediate: past instant and absent schedule are visible")
    void pastScheduleAndDefaultAreImmediate() throws Exception {
        createTask(supervisorAToken, studentA.internshipId(), "Past task",
                Instant.now().minusSeconds(3600).toString());
        createTask(supervisorAToken, studentA.internshipId(), "Plain task", null);
        assertThat(internTasks(studentA)).hasSize(2);
    }

    @Test
    @DisplayName("periodValidation: outside the internship period is refused with 422")
    void periodValidation() throws Exception {
        String payload = "{\"title\":\"Out of period\",\"visibleFrom\":\"%s\"}";
        MvcResult before = mockMvc.perform(post("/api/internships/" + studentA.internshipId() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.formatted(LocalDate.now().minusDays(11).atStartOfDay().toString() + ":00Z"))
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(422);
        assertThat(before.getResponse().getContentAsString()).contains("VISIBLE_FROM_OUTSIDE_PERIOD");

        MvcResult after = mockMvc.perform(post("/api/internships/" + studentA.internshipId() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.formatted(LocalDate.now().plusDays(81).atStartOfDay().toString() + ":00Z"))
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(422);
        assertThat(after.getResponse().getContentAsString()).contains("VISIBLE_FROM_OUTSIDE_PERIOD");
    }

    @Test
    @DisplayName("internCannotSchedule: a student sending visibleFrom is refused, never silently dropped")
    void internCannotSchedule() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internships/" + studentA.internshipId() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sneaky\",\"visibleFrom\":\""
                                + Instant.now().plusSeconds(3600) + "\"}")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("TASK_SCHEDULE_STAFF_ONLY");
    }

    @Test
    @DisplayName("rescheduleSemantics: update with null keeps the schedule, past makes it immediate")
    void rescheduleSemantics() throws Exception {
        String future = Instant.now().plusSeconds(3 * 24 * 3600).toString();
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "Resched", future);
        UUID taskId = UUID.fromString(created.path("id").asText());
        assertThat(internTasks(studentA)).isEmpty();

        // Null on update = no change (stays hidden).
        mockMvc.perform(put("/api/internships/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Resched v2\"}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());
        assertThat(internTasks(studentA)).isEmpty();
        assertThat(reloadTask(taskId).getVisibleFrom()).isNotNull();

        // Past instant = immediate again.
        mockMvc.perform(put("/api/internships/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Resched v3\",\"visibleFrom\":\""
                                + Instant.now().minusSeconds(60) + "\"}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());
        assertThat(internTasks(studentA)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Notify once on appearance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("notifyOnceOnAppearance: a due scheduled task notifies once; future tasks never; repeats never duplicate")
    void notifyOnceOnAppearance() throws Exception {
        createTask(supervisorAToken, studentA.internshipId(), "Due task",
                Instant.now().minusSeconds(3600).toString());
        createTask(supervisorAToken, studentA.internshipId(), "Later task",
                Instant.now().plusSeconds(5 * 24 * 3600).toString());

        visibilityScheduler.sweep();
        assertThat(scheduledVisibleCount(studentA)).isEqualTo(1);
        visibilityScheduler.sweep();
        visibilityScheduler.sweep();
        assertThat(scheduledVisibleCount(studentA)).isEqualTo(1);

        // The other student's sweep output never leaks across scope.
        assertThat(scheduledVisibleCount(studentB)).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // Journal denominator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("denominatorExcludesHidden: the validation detail total ignores not-yet-visible tasks")
    void denominatorExcludesHidden() throws Exception {
        createTask(supervisorAToken, studentA.internshipId(), "Visible one", null);
        createTask(supervisorAToken, studentA.internshipId(), "Visible two", null);
        createTask(supervisorAToken, studentA.internshipId(), "Hidden three",
                Instant.now().plusSeconds(4 * 24 * 3600).toString());

        MvcResult detail = mockMvc.perform(get("/api/internship-validation/" + studentA.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tasks = objectMapper.readTree(detail.getResponse().getContentAsString()).path("tasks");
        assertThat(tasks.path("total").asInt()).isEqualTo(2);
        assertThat(tasks.path("items")).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Review: scope + reason + transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reviewIsScoped: another supervisor's task is 404, a student caller is 403")
    void reviewIsScoped() throws Exception {
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "Review me", null);
        UUID taskId = UUID.fromString(created.path("id").asText());
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("denyRequiresReason: blank denial is 422 and changes nothing")
    void denyRequiresReason() throws Exception {
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "No reason", null);
        UUID taskId = UUID.fromString(created.path("id").asText());
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk());

        MvcResult denied = mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":false,\"comment\":\"   \"}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(422);
        assertThat(denied.getResponse().getContentAsString()).contains("REVIEW_REASON_REQUIRED");
        assertThat(reloadTask(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("approveAndDenyFlow: COMPLETED to APPROVED counts as done; DENIED returns with the reason")
    void approveAndDenyFlow() throws Exception {
        JsonNode ok = createTask(supervisorAToken, studentA.internshipId(), "Good work", null);
        UUID okId = UUID.fromString(ok.path("id").asText());
        JsonNode ko = createTask(supervisorAToken, studentA.internshipId(), "Weak work", null);
        UUID koId = UUID.fromString(ko.path("id").asText());
        for (UUID id : List.of(okId, koId)) {
            mockMvc.perform(patch("/api/internships/tasks/" + id + "/status?status=COMPLETED")
                            .header("Authorization", "Bearer " + studentA.internToken()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/internships/tasks/" + okId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/internships/tasks/" + koId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":false,\"comment\":\"Missing tests\"}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.reviewReason").value("Missing tests"));

        // The student reads both outcomes through the same board contract.
        List<String> statuses = internTasks(studentA).stream()
                .map(n -> n.path("status").asText()).toList();
        assertThat(statuses).contains("APPROVED", "DENIED");
    }

    @Test
    @DisplayName("reviewOnlyCompleted: approving a TODO task is refused")
    void reviewOnlyCompleted() throws Exception {
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "Too early", null);
        UUID taskId = UUID.fromString(created.path("id").asText());
        MvcResult result = mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("TASK_NOT_COMPLETED");
    }

    @Test
    @DisplayName("deleteIsScopedAndNotified: cross-supervisor delete is 404; own delete notifies the intern")
    void deleteIsScopedAndNotified() throws Exception {
        JsonNode created = createTask(supervisorAToken, studentA.internshipId(), "Doomed", null);
        UUID taskId = UUID.fromString(created.path("id").asText());

        mockMvc.perform(delete("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNoContent());

        List<JsonNode> notes = internNotifications(studentA);
        assertThat(notes.stream()
                .filter(n -> "TASK_DELETED".equals(n.path("type").asText(null)))
                .count()).isEqualTo(1);
    }
}
