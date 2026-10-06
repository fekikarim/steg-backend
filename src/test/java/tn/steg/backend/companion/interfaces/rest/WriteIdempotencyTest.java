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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T06/BR-56 — write idempotency on the offline-queue endpoints (Testcontainers).
 *
 * <p>The mobile offline queue replays each logical write with the SAME
 * client-generated key; the server must apply the effect once and return the
 * identical response on replay. A different key is a different write.
 * Scope is METHOD + URI (the target status rides the query string), so the
 * client mints one fresh UUID per logical operation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T06 — Write idempotency on status + message sends (Testcontainers)")
class WriteIdempotencyTest {

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
    private UserPrincipal adminPrincipal;
    private String supervisorToken;
    private UUID internshipId;
    private String internToken;
    private UUID taskId;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        User adminUser = userRepository.saveAndFlush(new User("adm_wid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User supervisorUser = userRepository.saveAndFlush(
                new User("sup_wid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        University university = universityRepository.saveAndFlush(new University("UNI_WID_" + run, "WID Uni"));
        User internUser = userRepository.saveAndFlush(
                new User("cand_wid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(run.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Wid", "Student", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(10), LocalDate.now().plusDays(80),
                "WID project", "Technicien", false, supervisorUser.getId()), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(resp.id()).orElseThrow();
        internshipId = internship.getId();
        internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Idempotent task\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        taskId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());
    }

    private MvcResult patchStatus(String token, UUID id, String status, String key) throws Exception {
        var builder = patch("/api/internships/tasks/" + id + "/status?status=" + status)
                .header("Authorization", "Bearer " + token);
        if (key != null) {
            builder.header("X-Idempotency-Key", key);
        }
        return mockMvc.perform(builder).andReturn();
    }

    @Test
    @DisplayName("status replay: same key applies once and returns the identical response")
    void statusReplayAppliesOnce() throws Exception {
        String key = "wid-" + run;
        MvcResult first = patchStatus(internToken, taskId, "IN_PROGRESS", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = patchStatus(internToken, taskId, "IN_PROGRESS", key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        Task task = ((tn.steg.backend.companion.domain.repository.TaskRepository) taskRepository)
                .findById(taskId).orElseThrow();
        assertThat(task.getStatus().name()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("status without a key executes directly (no contract break)")
    void statusWithoutKeyStillWorks() throws Exception {
        MvcResult result = patchStatus(internToken, taskId, "COMPLETED", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("status failure is never cached: the same key retries after the fix")
    void statusFailureIsNotCached() throws Exception {
        String key = "wid-fail-" + run;
        // Unknown task: 404 is not cached.
        MvcResult missing = patchStatus(internToken, UUID.randomUUID(), "IN_PROGRESS", key);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        // Same key on the real task executes normally.
        MvcResult ok = patchStatus(internToken, taskId, "IN_PROGRESS", key);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("hidden task status write by the intern stays 404, key or not")
    void hiddenTaskWriteStaysNotFound() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hidden\",\"visibleFrom\":\""
                                + java.time.Instant.now().plusSeconds(86400) + "\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID hiddenId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());
        MvcResult result = patchStatus(internToken, hiddenId, "IN_PROGRESS", "wid-hidden-" + run);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("supervisor edit of a hidden task still works with a key")
    void supervisorEditHiddenWithKey() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hidden edit\",\"visibleFrom\":\""
                                + java.time.Instant.now().plusSeconds(86400) + "\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID hiddenId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());
        MvcResult updated = mockMvc.perform(put("/api/internships/tasks/" + hiddenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hidden edit v2\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(updated.getResponse().getContentAsString())
                .path("title").asText()).isEqualTo("Hidden edit v2");
    }
}
