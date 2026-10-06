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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T06 §6–§7 — hidden scheduled tasks never leak through realtime (Testcontainers).
 *
 * <p>Mutating a not-yet-visible task (edit, status change, delete, assign)
 * still notifies the supervisor, but the intern receives nothing — neither
 * title nor existence. The visibility sweep remains the intern's single
 * source for the appearance event.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T06 — Hidden tasks never leak through notifications (Testcontainers)")
class HiddenTaskNotificationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String run;
    private UserPrincipal adminPrincipal;
    private String supervisorToken;
    private String adminToken;
    private UUID internshipId;
    private String internToken;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        User adminUser = userRepository.saveAndFlush(new User("adm_htn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminToken = jwtService.generateAccessToken(
                adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User supervisorUser = userRepository.saveAndFlush(
                new User("sup_htn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        University university = universityRepository.saveAndFlush(new University("UNI_HTN_" + run, "HTN Uni"));
        User internUser = userRepository.saveAndFlush(
                new User("cand_htn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(run.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Hid", "Den", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);
        internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(10), LocalDate.now().plusDays(80),
                "HTN project", "Technicien", false, supervisorUser.getId()), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository)
                internshipRepository).findById(resp.id()).orElseThrow();
        internshipId = internship.getId();
    }

    private UUID createTask(String title, String visibleFromIso) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"description\":\"d\""
                + (visibleFromIso == null ? "" : ",\"visibleFrom\":\"" + visibleFromIso + "\"") + "}";
        MvcResult result = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("id").asText());
    }

    private List<JsonNode> notifications(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        List<JsonNode> rows = new ArrayList<>();
        content.forEach(rows::add);
        return rows;
    }

    private boolean internSeesTitle(String title) throws Exception {
        return notifications(internToken).stream()
                .anyMatch(n -> n.path("message").asText("").contains(title)
                        || n.path("title").asText("").contains(title));
    }

    private long countType(String token, String type) throws Exception {
        return notifications(token).stream()
                .filter(n -> type.equals(n.path("type").asText(null)))
                .count();
    }

    @Test
    @DisplayName("hidden create/edit/status/delete notify the supervisor only, never the intern")
    void hiddenMutationsNotifySupervisorOnly() throws Exception {
        String future = Instant.now().plusSeconds(3 * 24 * 3600).toString();
        String title = "Secret-" + run;
        UUID taskId = createTask(title, future);

        // Edit the hidden task.
        mockMvc.perform(put("/api/internships/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + " v2\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());

        // Flip a visible task to COMPLETED then review it is covered
        // elsewhere; here the hidden task cannot move from the intern side.
        assertThat(internSeesTitle(title)).isFalse();
        assertThat(internSeesTitle(title + " v2")).isFalse();
        assertThat(countType(supervisorToken, "TASK_UPDATED")).isEqualTo(1);

        // Delete the hidden task: the intern never learns it existed.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isNoContent());
        assertThat(internSeesTitle(title)).isFalse();
        assertThat(countType(supervisorToken, "TASK_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("visible task mutations still notify both parties")
    void visibleMutationsNotifyBoth() throws Exception {
        String title = "Open-" + run;
        UUID taskId = createTask(title, null);

        mockMvc.perform(put("/api/internships/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + " v2\"}")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());

        assertThat(internSeesTitle(title + " v2")).isTrue();
        assertThat(countType(supervisorToken, "TASK_UPDATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("task payloads never carry classification data")
    void taskPayloadsCarryNoClassification() throws Exception {
        UUID taskId = createTask("Payload-" + run, null);
        MvcResult single = mockMvc.perform(get("/api/internships/tasks/" + taskId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = single.getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("categor");
        assertThat(body.toLowerCase()).doesNotContain("classif");

        MvcResult notes = mockMvc.perform(get("/api/notifications")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        String payloads = notes.getResponse().getContentAsString().toLowerCase();
        assertThat(payloads).doesNotContain("categor");
        assertThat(payloads).doesNotContain("task_category");
    }
}
