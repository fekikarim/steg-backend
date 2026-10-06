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
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T03 student task classification (Testcontainers): category lifecycle,
 * server-side validation, manual compare-and-set assignment, student
 * scoping (cross-student 404, supervisor/admin 403), status independence,
 * and proof that no staff-served task payload carries the category.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T03 — Task categories: lifecycle, validation, scoping (Testcontainers)")
class TaskCategoryIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String run;
    private User adminUser;
    private User supervisorUser;
    private UserPrincipal adminPrincipal;
    private String adminToken;
    private String supervisorToken;
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

        adminUser = userRepository.saveAndFlush(new User("adm_tc_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminToken = jwtService.generateAccessToken(
                adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(
                new User("sup_tc_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        university = universityRepository.saveAndFlush(new University("UNI_TC_" + run, "TC Uni"));

        studentA = fixture("TA" + run);
        studentB = fixture("TB" + run);
    }

    private Fixture fixture(String tag) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_tc_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "TC project " + tag, "Technicien", false, supervisorUser.getId()), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(resp.id()).orElseThrow();
        String token = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(internship.getId(), internUser, token);
    }

    private Task studentTask(Fixture f, String title) {
        return taskRepository.saveAndFlush(new Task(
                ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                        internshipRepository).findById(f.internshipId()).orElseThrow(),
                supervisorUser, title, "desc"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, String token, String json) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json == null ? "{}" : json)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private MvcResult putJson(String url, String token, String json) throws Exception {
        return mockMvc.perform(put(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json == null ? "{}" : json)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private UUID createCategory(Fixture f, String name, String color) throws Exception {
        MvcResult result = postJson("/api/internships/" + f.internshipId() + "/task-categories",
                f.internToken(), "{\"name\":\"" + name + "\",\"color\":" + (color == null ? "null" : "\"" + color + "\"") + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode board(Fixture f) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/internships/" + f.internshipId() + "/task-categories")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    private Task reloadTask(UUID id) {
        return ((tn.steg.backend.companion.domain.repository.TaskRepository) taskRepository)
                .findById(id).orElseThrow();
    }

    // Lifecycle + board persistence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("crudLifecycle: create, board, rename, reorder, assign, delete returns tasks to unclassified")
    void crudLifecycle() throws Exception {
        Task task = studentTask(studentA, "Lifecycle task");
        UUID frontend = createCategory(studentA, "Frontend", "blue");
        UUID backend = createCategory(studentA, "Backend", null);

        JsonNode created = board(studentA);
        assertThat(created.path("categories")).hasSize(2);
        assertThat(created.path("assignments").size()).isEqualTo(0);

        // Assign with compare-and-set (still unclassified).
        MvcResult assigned = putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + frontend + "\",\"expectedCategoryId\":null,\"force\":false}");
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(board(studentA).path("assignments").path(task.getId().toString()).asText())
                .isEqualTo(frontend.toString());

        // Rename keeps the assignment; reorder swaps positions.
        MvcResult renamed = putJson("/api/internships/task-categories/" + frontend,
                studentA.internToken(), "{\"name\":\"Web\",\"color\":\"green\"}");
        assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(renamed.getResponse().getContentAsString()).path("name").asText())
                .isEqualTo("Web");

        MvcResult reordered = putJson("/api/internships/task-categories/order",
                studentA.internToken(),
                "{\"orderedIds\":[\"" + backend + "\",\"" + frontend + "\"]}");
        assertThat(reordered.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = objectMapper.readTree(reordered.getResponse().getContentAsString());
        assertThat(order.get(0).path("id").asText()).isEqualTo(backend.toString());

        // Deleting a category returns its tasks to unclassified — nothing lost.
        MvcResult deleted = mockMvc.perform(delete("/api/internships/task-categories/" + frontend)
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andReturn();
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        JsonNode after = board(studentA);
        assertThat(after.path("categories")).hasSize(1);
        assertThat(after.path("assignments").size()).isEqualTo(0);
        Task reloaded = reloadTask(task.getId());
        assertThat(reloaded.getTaskCategory()).isNull();
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validation: blank, too-long, duplicate (case-insensitive) and bad colour are refused")
    void validation() throws Exception {
        createCategory(studentA, "Frontend", "blue");

        MvcResult blank = postJson("/api/internships/" + studentA.internshipId() + "/task-categories",
                studentA.internToken(), "{\"name\":\"   \"}");
        assertThat(blank.getResponse().getStatus()).isEqualTo(422);
        assertThat(blank.getResponse().getContentAsString()).contains("CATEGORY_NAME_INVALID");

        MvcResult tooLong = postJson("/api/internships/" + studentA.internshipId() + "/task-categories",
                studentA.internToken(), "{\"name\":\"" + "x".repeat(41) + "\"}");
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(422);
        assertThat(tooLong.getResponse().getContentAsString()).contains("CATEGORY_NAME_INVALID");

        MvcResult duplicate = postJson("/api/internships/" + studentA.internshipId() + "/task-categories",
                studentA.internToken(), "{\"name\":\"  frontend \"}");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        assertThat(duplicate.getResponse().getContentAsString()).contains("CATEGORY_NAME_DUPLICATE");

        MvcResult badColor = postJson("/api/internships/" + studentA.internshipId() + "/task-categories",
                studentA.internToken(), "{\"name\":\"Mobile\",\"color\":\"chartreuse\"}");
        assertThat(badColor.getResponse().getStatus()).isEqualTo(422);
        assertThat(badColor.getResponse().getContentAsString()).contains("CATEGORY_COLOR_INVALID");

        MvcResult badReorder = putJson("/api/internships/task-categories/order",
                studentA.internToken(), "{\"orderedIds\":[\"" + UUID.randomUUID() + "\"]}");
        assertThat(badReorder.getResponse().getStatus()).isEqualTo(422);
        assertThat(badReorder.getResponse().getContentAsString()).contains("CATEGORY_REORDER_INVALID");
    }

    // ------------------------------------------------------------------
    // Compare-and-set assignment + status independence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("assignIsCompareAndSet: stale expectation is 409, force overrides, status never changes")
    void assignIsCompareAndSet() throws Exception {
        Task task = studentTask(studentA, "CAS task");
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(task);
        UUID catA = createCategory(studentA, "CatA", null);
        UUID catB = createCategory(studentA, "CatB", null);

        putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + catA + "\",\"expectedCategoryId\":null,\"force\":false}");

        // Stale expectation (still believes unclassified) → 409, untouched.
        MvcResult stale = putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + catB + "\",\"expectedCategoryId\":null,\"force\":false}");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("CATEGORY_CHANGED");

        // Explicit force after the conflict wins.
        MvcResult forced = putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + catB + "\",\"expectedCategoryId\":null,\"force\":true}");
        assertThat(forced.getResponse().getStatus()).isEqualTo(200);

        // Clearing works with the fresh expectation.
        MvcResult cleared = putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":null,\"expectedCategoryId\":\"" + catB + "\",\"force\":false}");
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);

        Task reloaded = reloadTask(task.getId());
        assertThat(reloaded.getTaskCategory()).isNull();
        // BR-19: classification never changed the workflow status.
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    // ------------------------------------------------------------------
    // Scoping: cross-student 404, staff 403, staff DTOs carry no category
    // ------------------------------------------------------------------

    @Test
    @DisplayName("crossStudentIsNotFound: another student's category, task and board are 404")
    void crossStudentIsNotFound() throws Exception {
        Task taskB = studentTask(studentB, "B task");
        UUID catB = createCategory(studentB, "BCategory", null);

        // A's board never mentions B's data.
        assertThat(board(studentA).path("categories")).isEmpty();

        // A assigning B's task or B's category → 404 (no leak).
        MvcResult foreignTask = putJson("/api/internships/tasks/" + taskB.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":null,\"expectedCategoryId\":null,\"force\":true}");
        assertThat(foreignTask.getResponse().getStatus()).isEqualTo(404);

        MvcResult foreignCategory = putJson("/api/internships/tasks/" + studentTask(studentA, "A task").getId()
                        + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + catB + "\",\"expectedCategoryId\":null,\"force\":true}");
        assertThat(foreignCategory.getResponse().getStatus()).isEqualTo(404);

        // A reading B's board → 404.
        MvcResult foreignBoard = mockMvc.perform(get("/api/internships/" + studentB.internshipId()
                        + "/task-categories")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andReturn();
        assertThat(foreignBoard.getResponse().getStatus()).isEqualTo(404);

        // A renaming B's category → 404.
        MvcResult foreignRename = putJson("/api/internships/task-categories/" + catB,
                studentA.internToken(), "{\"name\":\"Hijacked\"}");
        assertThat(foreignRename.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("staffIsForbidden: supervisor and admin tokens get 403 on every classification endpoint")
    void staffIsForbidden() throws Exception {
        UUID cat = createCategory(studentA, "Private", null);
        Task task = studentTask(studentA, "Private task");
        String board = "/api/internships/" + studentA.internshipId() + "/task-categories";

        for (String token : List.of(supervisorToken, adminToken)) {
            mockMvc.perform(get(board).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(board).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/internships/task-categories/" + cat)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Y\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/internships/task-categories/" + cat)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/internships/tasks/" + task.getId() + "/category")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(board + "/suggest")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(board + "/apply")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/internships/task-categories/apply-batches/" + UUID.randomUUID() + "/undo")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("staffTaskPayloadHasNoCategory: no task DTO served to supervisors contains the category field")
    void staffTaskPayloadHasNoCategory() throws Exception {
        Task task = studentTask(studentA, "Classified task");
        UUID cat = createCategory(studentA, "Secret", null);
        putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null,\"force\":true}");

        // List + single read as the owning supervisor: raw payload must not
        // mention the category anywhere (BR-08).
        MvcResult list = mockMvc.perform(get("/api/internships/" + studentA.internshipId() + "/tasks")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        String listBody = list.getResponse().getContentAsString();
        assertThat(listBody.toLowerCase()).doesNotContain("category");

        MvcResult single = mockMvc.perform(get("/api/internships/tasks/" + task.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        String singleBody = single.getResponse().getContentAsString();
        assertThat(singleBody.toLowerCase()).doesNotContain("category");
        assertThat(singleBody).contains(task.getId().toString());
    }

    @Test
    @DisplayName("unauthenticatedIs401: classification endpoints require authentication")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/api/internships/" + studentA.internshipId() + "/task-categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("statusUntouchedByCategoryLifecycle: create/rename/delete never mutate task status")
    void statusUntouchedByCategoryLifecycle() throws Exception {
        Task task = studentTask(studentA, "Status probe");
        task.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(task);

        UUID cat = createCategory(studentA, "Probe", null);
        putJson("/api/internships/tasks/" + task.getId() + "/category",
                studentA.internToken(),
                "{\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null,\"force\":false}");
        putJson("/api/internships/task-categories/" + cat,
                studentA.internToken(), "{\"name\":\"Probe2\"}");
        mockMvc.perform(delete("/api/internships/task-categories/" + cat)
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isNoContent());

        List<TaskStatus> seen = new ArrayList<>();
        seen.add(reloadTask(task.getId()).getStatus());
        assertThat(seen).containsExactly(TaskStatus.COMPLETED);
    }
}
