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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.SupervisorManagementService;
import tn.steg.backend.internship.application.dto.AssignCandidateRequest;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T07 — Supervisor-management assignment paths rebind the PRIVATE thread (BR-38)")
class ConversationReassignmentTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

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
    private SupervisorManagementService supervisorManagementService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User adminUser;
    private User supA;
    private User supB;
    private User internUser;
    private User outsiderUser;

    private String adminToken;
    private String supAToken;
    private String supBToken;
    private String internToken;
    private String outsiderToken;

    private Employee supAEmployee;
    private Candidate internCandidate;
    private Internship internship;
    private UUID privateConversationId;
    private Department dept;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        adminUser = newUser("t07_admin", null);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supA = newUser("t07_supA", "SUPERVISOR");
        supAToken = jwtService.generateAccessToken(supA.getId(), supA.getEmail(), List.of("ROLE_SUPERVISOR"));

        supB = newUser("t07_supB", "SUPERVISOR");
        supBToken = jwtService.generateAccessToken(supB.getId(), supB.getEmail(), List.of("ROLE_SUPERVISOR"));

        internUser = newUser("t07_intern", null);
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        outsiderUser = newUser("t07_outsider", null);
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(),
                List.of("ROLE_CANDIDATE"));

        dept = departmentRepository.saveAndFlush(new Department("DIR_T07", "Direction T07", "T07"));

        supAEmployee = new Employee("EMP-T07-A", "Sana", "Trabelsi", dept);
        supAEmployee.setUser(supA);
        supAEmployee = employeeRepository.saveAndFlush(supAEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_T07", "INSAT Tunis"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest("11223344".getBytes(StandardCharsets.UTF_8)));
        internCandidate = new Candidate("Nour", "Ben Ali", "t07_intern@steg.com", cinHash, uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("11223344");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "STEG T07 Project",
                "Ingénieur",
                false), adminPrincipal);
        internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();

        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supAEmployee.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "T07 initial assignment"),
                adminPrincipal);

        MvcResult listRes = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode conversations = objectMapper.readTree(listRes.getResponse().getContentAsString());
        assertThat(conversations.isArray()).isTrue();
        assertThat(conversations.size()).isGreaterThanOrEqualTo(1);
        privateConversationId = UUID.fromString(conversations.get(0).get("id").asText());

        // Seed one message so history preservation is observable after rotation.
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("hello from intern"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("reassignSupervisor rotates the thread: old supervisor blocked, new supervisor + intern keep history, no duplicate")
    void reassignSupervisorRotatesPrivateThread() throws Exception {
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorManagementService.reassignSupervisor(
                internship.getId(), new ReassignSupervisorRequest(supB.getId(), "T07 rotation"), adminPrincipal);

        // Old supervisor loses access on the same thread (read + send).
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supAToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("stale supervisor"))))
                .andExpect(status().isForbidden());

        // New supervisor reads the preserved history and can write.
        MvcResult history = mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supBToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(history.getResponse().getContentAsString()).contains("hello from intern");
        mockMvc.perform(post("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + supBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("welcome aboard"))))
                .andExpect(status().isCreated());

        // The intern keeps access and sees both messages.
        MvcResult internHistory = mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(internHistory.getResponse().getContentAsString()).contains("hello from intern");

        // Exactly one PRIVATE thread is visible to the intern, with the same id.
        MvcResult internList = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        long privates = 0;
        for (JsonNode c : objectMapper.readTree(internList.getResponse().getContentAsString())) {
            if (c.get("type").asText().equals("PRIVATE")
                    && c.get("id").asText().equals(privateConversationId.toString())) {
                privates++;
            }
        }
        assertThat(privates).isEqualTo(1L);

        // The thread is gone from the old supervisor's list and present in the new one's.
        assertThat(conversationIds(supAToken)).doesNotContain(privateConversationId.toString());
        assertThat(conversationIds(supBToken)).contains(privateConversationId.toString());

        // An unrelated user learns nothing (no leak, no 200).
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        assertThat(conversationIds(outsiderToken)).doesNotContain(privateConversationId.toString());
    }

    @Test
    @DisplayName("assignCandidate creates the PRIVATE thread so both members can read and write")
    void assignCandidateCreatesPrivateThread() throws Exception {
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User intern2 = newUser("t07_intern2", null);
        String intern2Token = jwtService.generateAccessToken(intern2.getId(), intern2.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));
        University uni = universityRepository.saveAndFlush(new University("SUPCOM_T07", "SUPCOM Tunis"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder()
                .encodeToString(digest.digest("55667788".getBytes(StandardCharsets.UTF_8)));
        Candidate candidate2 = new Candidate("Karim", "Feki", "t07_intern2@steg.com", cinHash, uni);
        candidate2.setUser(intern2);
        candidate2.setNationalIdEncrypted("55667788");
        candidate2 = candidateRepository.saveAndFlush(candidate2);

        InternshipResponse created2 = internshipService.createManual(new InternshipCreateManualRequest(
                candidate2.getId(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(60),
                "Second T07 project",
                "Ingénieur",
                false), adminPrincipal);
        UUID internship2Id = created2.id();

        supervisorManagementService.assignCandidate(
                supB.getId(), new AssignCandidateRequest(internship2Id, null), adminPrincipal);

        MvcResult list = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + intern2Token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode conversations = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(conversations.size()).isEqualTo(1);
        assertThat(conversations.get(0).get("type").asText()).isEqualTo("PRIVATE");
        UUID thread2 = UUID.fromString(conversations.get(0).get("id").asText());

        mockMvc.perform(post("/api/conversations/" + thread2 + "/messages")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("hi supervisor"))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/conversations/" + thread2 + "/messages")
                        .header("Authorization", "Bearer " + supBToken))
                .andExpect(status().isOk());

        // The first internship's supervisor cannot reach the second thread.
        mockMvc.perform(get("/api/conversations/" + thread2 + "/messages")
                        .header("Authorization", "Bearer " + supAToken))
                .andExpect(status().isForbidden());
        assertThat(thread2).isNotEqualTo(privateConversationId);
    }

    private List<String> conversationIds(String token) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode c : objectMapper.readTree(list.getResponse().getContentAsString())) {
            ids.add(c.get("id").asText());
        }
        return ids;
    }

    private User newUser(String prefix, String roleCode) {
        User user = new User(prefix + "@steg.com", "hash", UserStatus.ACTIVE);
        if (roleCode != null) {
            Role role = roleRepository.findByCode(roleCode).orElse(null);
            assertThat(role).as("seeded role " + roleCode).isNotNull();
            user.getAssignedRoles().add(role);
        }
        return userRepository.saveAndFlush(user);
    }
}
