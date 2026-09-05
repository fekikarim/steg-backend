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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
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
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.messaging.application.dto.CreateGroupRequest;
import tn.steg.backend.messaging.application.dto.SendMessageRequest;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Messaging Module Integration Tests (A9)")
class MessagingIntegrationTest {

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
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User hrUser;
    private User supervisorUser;
    private User internUser;
    private User outsiderUser;
    private User secondInternUser;

    private String hrToken;
    private String supervisorToken;
    private String internToken;
    private String outsiderToken;
    private String secondInternToken;

    private Employee supervisorEmployee;
    private Candidate internCandidate;
    private Candidate secondInternCandidate;
    private Internship internship;
    private Internship secondInternship;
    private UUID privateConversationId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        hrUser = userRepository.saveAndFlush(new User("hr_msg@steg.com", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_msg@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("intern_msg@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        outsiderUser = userRepository.saveAndFlush(new User("outsider_msg@steg.com", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(), List.of("ROLE_CANDIDATE"));

        secondInternUser = userRepository.saveAndFlush(new User("intern2_msg@steg.com", "hash", UserStatus.ACTIVE));
        secondInternToken = jwtService.generateAccessToken(secondInternUser.getId(), secondInternUser.getEmail(), List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_MSG", "Direction Messaging", "MSG"));

        supervisorEmployee = new Employee("EMP-MSG-1", "Salma", "Ben Ammar", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_MSG", "INSAT Tunis"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest("99887766".getBytes(StandardCharsets.UTF_8)));
        internCandidate = new Candidate("Yasmine", "Haddad", "intern_msg@steg.com", cinHash, uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("99887766");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        String cinHash2 = Base64.getEncoder().encodeToString(digest.digest("55443322".getBytes(StandardCharsets.UTF_8)));
        secondInternCandidate = new Candidate("Omar", "Feki", "intern2_msg@steg.com", cinHash2, uni);
        secondInternCandidate.setUser(secondInternUser);
        secondInternCandidate.setNationalIdEncrypted("55443322");
        secondInternCandidate = candidateRepository.saveAndFlush(secondInternCandidate);

        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "STEG Messaging Project",
                "Ingénieur",
                false), hrPrincipal);
        internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();

        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "Messaging assignment"), hrPrincipal);

        InternshipResponse created2 = internshipService.createManual(new InternshipCreateManualRequest(
                secondInternCandidate.getId(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(60),
                "Second project",
                "Ingénieur",
                false), hrPrincipal);
        secondInternship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created2.id()).orElseThrow();
        internshipService.assign(secondInternship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(5), LocalDate.now().plusDays(60), "Second assignment"), hrPrincipal);

        // Resolve the auto-created private thread for the first internship
        MvcResult listRes = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode conversations = objectMapper.readTree(listRes.getResponse().getContentAsString());
        assertThat(conversations.isArray()).isTrue();
        assertThat(conversations.size()).isGreaterThanOrEqualTo(1);
        privateConversationId = UUID.fromString(conversations.get(0).get("id").asText());
    }

    @Test
    @DisplayName("Private thread is auto-created on ACTIVE assignment with exactly intern + supervisor")
    void privateThreadAutoCreatedOnAssign() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/conversations/" + privateConversationId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PRIVATE"))
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(node.get("members").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Members can send and read; outsider is denied (403) on history and send")
    void membershipEnforcementPositiveAndNegative() throws Exception {
        SendMessageRequest send = new SendMessageRequest("Hello, supervisor — daily update ready.");

        MvcResult sent = mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(send)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequenceNumber").value(1))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andReturn();
        UUID messageId = UUID.fromString(objectMapper.readTree(sent.getResponse().getContentAsString()).get("id").asText());
        assertThat(messageId).isNotNull();

        // Supervisor (other member) can read history
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        // Outsider cannot read or send
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(send)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Guessing another internship's private conversation id does not leak data (403/404)")
    void idorGuessingAnotherPrivateConversationIsBlocked() throws Exception {
        // Resolve second internship's private thread as the supervisor, then try it as the first intern
        MvcResult secondList = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + secondInternToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondConvs = objectMapper.readTree(secondList.getResponse().getContentAsString());
        assertThat(secondConvs.size()).isGreaterThanOrEqualTo(1);
        UUID secondConvId = null;
        for (JsonNode c : secondConvs) {
            UUID id = UUID.fromString(c.get("id").asText());
            if (!id.equals(privateConversationId)) {
                secondConvId = id;
                break;
            }
        }
        assertThat(secondConvId).isNotNull();

        // First intern guesses/increments to the second conversation id
        mockMvc.perform(get("/api/conversations/" + secondConvId + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/conversations/" + secondConvId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());

        // Random unknown UUID also yields 403/404 (never 200 with data)
        MvcResult unknownRes = mockMvc.perform(get("/api/conversations/" + UUID.randomUUID() + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andReturn();
        assertThat(unknownRes.getResponse().getStatus()).isIn(403, 404);
    }

    @Test
    @DisplayName("Message ordering is monotonic via sequenceNumber (never sentAt)")
    void messageOrderingIsMonotonic() throws Exception {
        for (int i = 1; i <= 15; i++) {
            mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                            .header("Authorization", "Bearer " + internToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SendMessageRequest("ordered-msg-" + i))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sequenceNumber").value(i));
        }

        MvcResult history = mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "30")
                        .param("sort", "sequenceNumber,asc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(history.getResponse().getContentAsString());
        JsonNode content = page.get("content");
        assertThat(content.size()).isEqualTo(15);
        for (int i = 0; i < 15; i++) {
            assertThat(content.get(i).get("sequenceNumber").asLong()).isEqualTo(i + 1L);
            assertThat(content.get(i).get("content").asText()).isEqualTo("ordered-msg-" + (i + 1));
        }
    }

    @Test
    @DisplayName("Soft-delete redacts content but preserves history row")
    void softDeleteRedactsContent() throws Exception {
        MvcResult sent = mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("to be deleted"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(objectMapper.readTree(sent.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/conversations/messages/" + messageId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isNoContent());

        MvcResult history = mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("size", "30")
                        .param("sort", "sequenceNumber,asc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(history.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("content").asText()).isEqualTo("[message deleted]");
        assertThat(content.get(0).get("status").asText()).isEqualTo("DELETED");
        assertThat(content.get(0).get("deletedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("Sender can edit message; non-sender cannot")
    void editMessageLifecycle() throws Exception {
        MvcResult sent = mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("original"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(objectMapper.readTree(sent.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(patch("/api/conversations/messages/" + messageId)
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("edited content"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited content"))
                .andExpect(jsonPath("$.status").value("EDITED"));

        mockMvc.perform(patch("/api/conversations/messages/" + messageId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("hijack"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GROUP conversation is scoped to current interns; outsiders rejected")
    void groupConversationScopedToCurrentInterns() throws Exception {
        CreateGroupRequest groupReq = new CreateGroupRequest("Interns —互相帮助 group", List.of(secondInternUser.getId()));

        MvcResult created = mockMvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andReturn();
        UUID groupId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        // Second intern (member) can send
        mockMvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + secondInternToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("hello group"))))
                .andExpect(status().isCreated());

        // Outsider (no ACTIVE internship) cannot be added and cannot read
        mockMvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("intrude"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Read receipts and unread counts work per user")
    void readReceiptsAndUnreadCounts() throws Exception {
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("msg-1"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("msg-2"))))
                .andExpect(status().isCreated());

        MvcResult unread = mockMvc.perform(get("/api/conversations/unread/counts")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(unread.getResponse().getContentAsString()).size()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/read")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upToSequenceNumber\":2}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Leaving preserves history for others but blocks the leaver")
    void leavingBlocksLeaverButPreservesHistory() throws Exception {
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("before leave"))))
                .andExpect(status().isCreated());

        // Supervisor leaves
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/leave")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isNoContent());

        // Leaver can no longer read
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        // Remaining member still sees history
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
    }
}
