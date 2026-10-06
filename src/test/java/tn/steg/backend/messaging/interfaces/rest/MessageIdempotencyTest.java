package tn.steg.backend.messaging.interfaces.rest;

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
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T06/BR-56 — message-send idempotency on the REST fallback (Testcontainers).
 *
 * <p>The mobile offline queue replays each logical message with the SAME
 * client-generated key; the server must persist one message and return the
 * identical response on replay.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T06 — Message send idempotency (Testcontainers)")
class MessageIdempotencyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private tn.steg.backend.internship.infrastructure.persistence.InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String run;
    private String supervisorToken;
    private String internToken;
    private UUID conversationId;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        User adminUser = userRepository.saveAndFlush(new User("adm_mid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User supervisorUser = userRepository.saveAndFlush(
                new User("sup_mid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        Department department =
                departmentRepository.saveAndFlush(new Department("DIR_MID_" + run, "Mid Dept", "MD"));
        Employee employee = new Employee("EMP-MID-" + run, "Mid", "Sup", department);
        employee.setUser(supervisorUser);
        employee = employeeRepository.saveAndFlush(employee);

        University university =
                universityRepository.saveAndFlush(new University("UNI_MID_" + run, "MID Uni"));
        User internUser = userRepository.saveAndFlush(
                new User("cand_mid_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(run.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Mid", "Intern", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);
        internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(10), LocalDate.now().plusDays(60),
                "MID project", "Ingénieur", false), adminPrincipal);
        tn.steg.backend.internship.domain.model.Internship internship =
                ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                        .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                department.getId(), employee.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "MID assignment"),
                adminPrincipal);

        MvcResult list = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode conversations = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(conversations.isArray()).isTrue();
        assertThat(conversations.size()).isGreaterThan(0);
        conversationId = UUID.fromString(conversations.get(0).path("id").asText());
    }

    private MvcResult send(String token, String content, String key) throws Exception {
        var builder = post("/api/conversations/" + conversationId + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("content", content)))
                .header("Authorization", "Bearer " + token);
        if (key != null) {
            builder.header("X-Idempotency-Key", key);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private List<JsonNode> history() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        List<JsonNode> rows = new ArrayList<>();
        content.forEach(rows::add);
        return rows;
    }

    @Test
    @DisplayName("message replay: same key persists one message and returns the identical response")
    void messageReplayPersistsOnce() throws Exception {
        String key = "mid-" + run;
        MvcResult first = send(internToken, "Hello offline queue", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        MvcResult replay = send(internToken, "Hello offline queue", key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(history()).hasSize(1);
    }

    @Test
    @DisplayName("different keys are different messages")
    void differentKeysAreDifferentMessages() throws Exception {
        send(internToken, "One", "mid-a-" + run);
        send(internToken, "Two", "mid-b-" + run);
        assertThat(history()).hasSize(2);
    }

    @Test
    @DisplayName("send without a key still works (no contract break)")
    void sendWithoutKeyStillWorks() throws Exception {
        MvcResult result = send(supervisorToken, "No key here", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }
}
