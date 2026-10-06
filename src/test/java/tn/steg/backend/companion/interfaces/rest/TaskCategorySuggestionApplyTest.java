package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T03 AI classification proposals + accept/undo (Testcontainers, fake AI).
 *
 * <p>The Gemini client is a scripted fake — the real API is never called.
 * Suggestions persist nothing; accepting is a per-item compare-and-set; undo
 * restores exactly the tasks unchanged since the batch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T03 — AI classification proposals + apply/undo (Testcontainers, fake AI)")
class TaskCategorySuggestionApplyTest {

    /** Scripted fake: the real AI API is never called. */
    @TestConfiguration
    static class FakeAiConfig {
        @Bean
        @Primary
        AiCompletionClient aiCompletionClient() {
            return Mockito.mock(AiCompletionClient.class);
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiCompletionClient fakeAi;
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
        Mockito.reset(fakeAi);
        when(fakeAi.getModel()).thenReturn("fake-model");
        when(fakeAi.getProvider()).thenReturn("gemini");
        run = uid();

        adminUser = userRepository.saveAndFlush(new User("adm_ts_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        supervisorUser = userRepository.saveAndFlush(
                new User("sup_ts_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        university = universityRepository.saveAndFlush(new University("UNI_TS_" + run, "TS Uni"));

        studentA = fixture("SA" + run);
        studentB = fixture("SB" + run);
    }

    private Fixture fixture(String tag) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_ts_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "TS project " + tag, "Technicien", false, supervisorUser.getId()), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(resp.id()).orElseThrow();
        String token = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(internship.getId(), internUser, token);
    }

    private Internship internshipOf(Fixture f) {
        return ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(f.internshipId()).orElseThrow();
    }

    private Task studentTask(Fixture f, String title) {
        return taskRepository.saveAndFlush(new Task(internshipOf(f), supervisorUser, title, "desc"));
    }

    private UUID createCategory(Fixture f, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internships/" + f.internshipId() + "/task-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("id").asText());
    }

    private void scriptSuccess(String json) {
        when(fakeAi.complete(any(), anyList()))
                .thenReturn(AiCompletionResult.success(json, "fake-model", "gemini"));
    }

    private MvcResult suggest(Fixture f) throws Exception {
        return mockMvc.perform(post("/api/internships/" + f.internshipId() + "/task-categories/suggest")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andReturn();
    }

    private JsonNode apply(Fixture f, String itemsJson, String idempotencyKey) throws Exception {
        var builder = post("/api/internships/" + f.internshipId() + "/task-categories/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":" + itemsJson + "}")
                .header("Authorization", "Bearer " + f.internToken());
        if (idempotencyKey != null) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    private Task reloadTask(UUID id) {
        return ((tn.steg.backend.companion.domain.repository.TaskRepository) taskRepository)
                .findById(id).orElseThrow();
    }

    // Suggestions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("suggestReturnsProposalsOnly: existing + new names, nothing persisted")
    void suggestReturnsProposalsOnly() throws Exception {
        Task t1 = studentTask(studentA, "Build the login screen");
        Task t2 = studentTask(studentA, "Write the API docs");
        UUID frontend = createCategory(studentA, "Frontend");

        scriptSuccess("{\"proposals\":["
                + "{\"taskId\":\"" + t1.getId() + "\",\"categoryId\":\"" + frontend + "\",\"confidence\":0.9},"
                + "{\"taskId\":\"" + t2.getId() + "\",\"newCategoryName\":\"Docs\",\"confidence\":0.7}"
                + "]}");
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("proposals")).hasSize(2);
        assertThat(body.path("proposals").get(0).path("isNewCategory").asBoolean()).isFalse();
        assertThat(body.path("proposals").get(1).path("isNewCategory").asBoolean()).isTrue();
        assertThat(body.path("proposals").get(1).path("newCategoryName").asText()).isEqualTo("Docs");

        // Nothing persisted: tasks still unclassified, no category created.
        assertThat(reloadTask(t1.getId()).getTaskCategory()).isNull();
        assertThat(reloadTask(t2.getId()).getTaskCategory()).isNull();
        MvcResult board = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internships/" + studentA.internshipId() + "/task-categories")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(board.getResponse().getContentAsString()).path("categories")).hasSize(1);
    }

    @Test
    @DisplayName("suggestDropsUntrustedIds: unknown tasks, foreign categories and bad names are ignored")
    void suggestDropsUntrustedIds() throws Exception {
        Task mine = studentTask(studentA, "Mine");
        Task other = studentTask(studentB, "Other student's");
        UUID mine2 = studentTask(studentA, "Second").getId();
        UUID foreignCat = createCategory(studentB, "Foreign");
        UUID ownCat = createCategory(studentA, "Own");

        scriptSuccess("{\"proposals\":["
                + "{\"taskId\":\"" + mine.getId() + "\",\"categoryId\":\"" + ownCat + "\"},"
                + "{\"taskId\":\"" + other.getId() + "\",\"categoryId\":\"" + ownCat + "\"},"
                + "{\"taskId\":\"" + UUID.randomUUID() + "\",\"categoryId\":\"" + ownCat + "\"},"
                + "{\"taskId\":\"" + mine2 + "\",\"categoryId\":\"" + foreignCat + "\"},"
                + "{\"taskId\":\"" + mine2 + "\",\"newCategoryName\":\""
                + "x".repeat(41) + "\"},"
                + "{\"taskId\":\"" + mine2 + "\",\"newCategoryName\":\"Valid\"}"
                + "]}");
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode proposals = objectMapper.readTree(result.getResponse().getContentAsString()).path("proposals");
        // Only the own-task/own-category line and the valid new-name line survive.
        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0).path("taskId").asText()).isEqualTo(mine.getId().toString());
        assertThat(proposals.get(1).path("newCategoryName").asText()).isEqualTo("Valid");
    }

    @Test
    @DisplayName("suggestClassifiedTasksExcluded: already-classified tasks get no proposal")
    void suggestClassifiedTasksExcluded() throws Exception {
        Task done = studentTask(studentA, "Already done");
        UUID cat = createCategory(studentA, "Cat");
        mockMvc.perform(put("/api/internships/tasks/" + done.getId() + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null,\"force\":false}")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk());

        scriptSuccess("{\"proposals\":[{\"taskId\":\"" + done.getId() + "\",\"categoryId\":\"" + cat + "\"}]}");
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("proposals")).isEmpty();
    }

    @Test
    @DisplayName("suggestDuplicateProposalFirstWins: two proposals for one task yield one line")
    void suggestDuplicateProposalFirstWins() throws Exception {
        Task t = studentTask(studentA, "Dup");
        UUID cat = createCategory(studentA, "Cat");
        scriptSuccess("{\"proposals\":["
                + "{\"taskId\":\"" + t.getId() + "\",\"categoryId\":\"" + cat + "\"},"
                + "{\"taskId\":\"" + t.getId() + "\",\"newCategoryName\":\"Other\"}"
                + "]}");
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("proposals")).hasSize(1);
    }

    @Test
    @DisplayName("suggestMalformedRetriesOnceThen422: two bad outputs degrade to AI_GENERATION_INVALID")
    void suggestMalformedRetriesOnceThen422() throws Exception {
        studentTask(studentA, "Task");
        createCategory(studentA, "Cat");
        when(fakeAi.complete(any(), anyList()))
                .thenReturn(AiCompletionResult.success("not json at all", "fake-model", "gemini"));
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("AI_GENERATION_INVALID");
        verify(fakeAi, times(2)).complete(any(), anyList());
    }

    @Test
    @DisplayName("suggestAiDown503: transport failure degrades to AI_UNAVAILABLE with a manual fallback")
    void suggestAiDown503() throws Exception {
        studentTask(studentA, "Task");
        createCategory(studentA, "Cat");
        when(fakeAi.complete(any(), anyList())).thenThrow(new RuntimeException("boom"));
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("AI_UNAVAILABLE");
    }

    @Test
    @DisplayName("suggestWithoutCategories422: the AI call is refused with an explanation, nothing breaks")
    void suggestWithoutCategories422() throws Exception {
        studentTask(studentA, "Task");
        MvcResult result = suggest(studentA);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("CATEGORY_SUGGESTION_NO_CATEGORIES");
        verify(fakeAi, Mockito.never()).complete(any(), anyList());
    }

    // ------------------------------------------------------------------
    // Apply (compare-and-set, per-item) + race + idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("applyOneAndAll: single accept touches one task, accept-all reports per item")
    void applyOneAndAll() throws Exception {
        Task t1 = studentTask(studentA, "One");
        Task t2 = studentTask(studentA, "Two");
        UUID cat = createCategory(studentA, "Cat");

        JsonNode single = apply(studentA,
                "[{\"taskId\":\"" + t1.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                null);
        assertThat(single.path("items").get(0).path("status").asText()).isEqualTo("APPLIED");
        assertThat(single.path("batchId").asText()).isNotBlank();

        JsonNode all = apply(studentA,
                "[{\"taskId\":\"" + t1.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null},"
                        + "{\"taskId\":\"" + t2.getId() + "\",\"newCategoryName\":\"Fresh\",\"expectedCategoryId\":null},"
                        + "{\"taskId\":\"" + UUID.randomUUID() + "\",\"categoryId\":\"" + cat + "\"}]",
                null);
        assertThat(all.path("items").get(0).path("status").asText())
                .isEqualTo("SKIPPED_ALREADY_CLASSIFIED");
        assertThat(all.path("items").get(1).path("status").asText()).isEqualTo("APPLIED");
        assertThat(all.path("items").get(2).path("status").asText()).isEqualTo("NOT_FOUND");

        // t1 kept its first classification; t2 got the accepted new category.
        assertThat(reloadTask(t1.getId())
                .getTaskCategory().getId()).isEqualTo(cat);
        MvcResult boardAfter = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internships/" + studentA.internshipId() + "/task-categories")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk()).andReturn();
        JsonNode boardBody = objectMapper.readTree(boardAfter.getResponse().getContentAsString());
        String t2CategoryId = boardBody.path("assignments").path(t2.getId().toString()).asText();
        assertThat(t2CategoryId).isNotBlank();
        String freshName = "";
        for (JsonNode c : boardBody.path("categories")) {
            if (c.path("id").asText().equals(t2CategoryId)) {
                freshName = c.path("name").asText();
            }
        }
        assertThat(freshName).isEqualTo("Fresh");
        // t1's status never moved.
        assertThat(reloadTask(t1.getId()).getStatus())
                .isEqualTo(TaskStatus.TODO);
    }

    @Test
    @DisplayName("applyRaceIsSkipped: a task classified in the meantime is reported, never overwritten")
    void applyRaceIsSkipped() throws Exception {
        Task t = studentTask(studentA, "Racy");
        UUID first = createCategory(studentA, "First");
        UUID second = createCategory(studentA, "Second");

        // The student classifies the task after the proposal was generated.
        mockMvc.perform(put("/api/internships/tasks/" + t.getId() + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + first + "\",\"expectedCategoryId\":null,\"force\":false}")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk());

        // The stale accept-all (still believes unclassified) must skip it.
        JsonNode applied = apply(studentA,
                "[{\"taskId\":\"" + t.getId() + "\",\"categoryId\":\"" + second
                        + "\",\"expectedCategoryId\":null}]",
                null);
        assertThat(applied.path("items").get(0).path("status").asText())
                .isEqualTo("SKIPPED_ALREADY_CLASSIFIED");
        assertThat(reloadTask(t.getId())
                .getTaskCategory().getId()).isEqualTo(first);
    }

    @Test
    @DisplayName("applyIsIdempotent: replaying the same key replays the batch without duplicating")
    void applyIsIdempotent() throws Exception {
        Task t = studentTask(studentA, "Idem");
        UUID cat = createCategory(studentA, "Cat");
        String key = "idem-" + UUID.randomUUID();

        JsonNode first = apply(studentA,
                "[{\"taskId\":\"" + t.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                key);
        JsonNode replay = apply(studentA,
                "[{\"taskId\":\"" + t.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                key);
        assertThat(replay.path("batchId").asText()).isEqualTo(first.path("batchId").asText());
        assertThat(replay.path("items").get(0).path("status").asText()).isEqualTo("APPLIED");
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("undoRestoresBatch: applied tasks become unclassified again")
    void undoRestoresBatch() throws Exception {
        Task t1 = studentTask(studentA, "U1");
        Task t2 = studentTask(studentA, "U2");
        UUID cat = createCategory(studentA, "Cat");

        JsonNode applied = apply(studentA,
                "[{\"taskId\":\"" + t1.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null},"
                        + "{\"taskId\":\"" + t2.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                null);
        UUID batchId = UUID.fromString(applied.path("batchId").asText());

        MvcResult undone = mockMvc.perform(post(
                        "/api/internships/task-categories/apply-batches/" + batchId + "/undo")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(undone.getResponse().getContentAsString()).path("items");
        assertThat(items.get(0).path("status").asText()).isEqualTo("REVERTED");
        assertThat(items.get(1).path("status").asText()).isEqualTo("REVERTED");
        assertThat(reloadTask(t1.getId()).getTaskCategory()).isNull();
        assertThat(reloadTask(t2.getId()).getTaskCategory()).isNull();
    }

    @Test
    @DisplayName("undoNeverTouchesLaterEdits: a task changed after the batch keeps its new classification")
    void undoNeverTouchesLaterEdits() throws Exception {
        Task kept = studentTask(studentA, "Kept");
        Task moved = studentTask(studentA, "Moved");
        UUID cat = createCategory(studentA, "Cat");
        UUID other = createCategory(studentA, "Other");

        JsonNode applied = apply(studentA,
                "[{\"taskId\":\"" + kept.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null},"
                        + "{\"taskId\":\"" + moved.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                null);
        UUID batchId = UUID.fromString(applied.path("batchId").asText());

        // The student re-classifies one task after the batch.
        mockMvc.perform(put("/api/internships/tasks/" + moved.getId() + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + other + "\",\"expectedCategoryId\":\"" + cat
                                + "\",\"force\":false}")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk());

        MvcResult undone = mockMvc.perform(post(
                        "/api/internships/task-categories/apply-batches/" + batchId + "/undo")
                        .header("Authorization", "Bearer " + studentA.internToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(undone.getResponse().getContentAsString()).path("items");
        assertThat(items.get(0).path("status").asText()).isEqualTo("REVERTED");
        assertThat(items.get(1).path("status").asText()).isEqualTo("SKIPPED_CHANGED");

        assertThat(reloadTask(kept.getId()).getTaskCategory()).isNull();
        assertThat(reloadTask(moved.getId())
                .getTaskCategory().getId()).isEqualTo(other);
    }

    @Test
    @DisplayName("undoForeignBatchIsNotFound: another student's batch cannot be undone")
    void undoForeignBatchIsNotFound() throws Exception {
        Task t = studentTask(studentA, "Mine");
        UUID cat = createCategory(studentA, "Cat");
        JsonNode applied = apply(studentA,
                "[{\"taskId\":\"" + t.getId() + "\",\"categoryId\":\"" + cat + "\",\"expectedCategoryId\":null}]",
                null);
        UUID batchId = UUID.fromString(applied.path("batchId").asText());

        mockMvc.perform(post("/api/internships/task-categories/apply-batches/" + batchId + "/undo")
                        .header("Authorization", "Bearer " + studentB.internToken()))
                .andExpect(status().isNotFound());
    }
}
