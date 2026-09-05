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
import org.springframework.test.context.TestPropertySource;
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
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.messaging.application.dto.AddMemberRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production gate: completed-intern GROUP policy in {@code revoke} mode.
 * Default {@code retain} behavior is covered by {@code MessagingIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "steg.messaging.groups.completed-intern-access=revoke")
@Transactional
@DisplayName("Group Access Policy Tests — revoke mode (production gate)")
class GroupAccessPolicyTest {

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
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User supervisorUser;
    private User internUser;
    private User secondInternUser;

    private String supervisorToken;
    private String internToken;
    private String secondInternToken;

    private Internship internship;
    private UUID groupId;
    private UUID privateConversationId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        User hrUser = userRepository.saveAndFlush(new User("hr_gap@steg.com", "hash", UserStatus.ACTIVE));
        supervisorUser = userRepository.saveAndFlush(new User("sup_gap@steg.com", "hash", UserStatus.ACTIVE));
        internUser = userRepository.saveAndFlush(new User("intern_gap@steg.com", "hash", UserStatus.ACTIVE));
        secondInternUser = userRepository.saveAndFlush(new User("intern2_gap@steg.com", "hash", UserStatus.ACTIVE));

        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        secondInternToken = jwtService.generateAccessToken(secondInternUser.getId(), secondInternUser.getEmail(), List.of("ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_GAP", "Gap Dept", "G"));
        Employee supervisorEmployee = new Employee("EMP-GAP-1", "Gap", "Supervisor", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_GAP", "Gap University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Candidate internCandidate = new Candidate("Gap", "One",
                "intern_gap@steg.com",
                Base64.getEncoder().encodeToString(digest.digest("GAP1".getBytes(StandardCharsets.UTF_8))), uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("GAP1");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        Candidate secondCandidate = new Candidate("Gap", "Two",
                "intern2_gap@steg.com",
                Base64.getEncoder().encodeToString(digest.digest("GAP2".getBytes(StandardCharsets.UTF_8))), uni);
        secondCandidate.setUser(secondInternUser);
        secondCandidate.setNationalIdEncrypted("GAP2");
        secondCandidate = candidateRepository.saveAndFlush(secondCandidate);

        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(), LocalDate.now().minusDays(5), LocalDate.now().plusDays(60),
                "Gap Project", "Ingénieur", false), hrPrincipal);
        internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(5), LocalDate.now().plusDays(60), "gap"), hrPrincipal);

        InternshipResponse created2 = internshipService.createManual(new InternshipCreateManualRequest(
                secondCandidate.getId(), LocalDate.now().minusDays(5), LocalDate.now().plusDays(60),
                "Gap Project 2", "Ingénieur", false), hrPrincipal);
        Internship internship2 = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created2.id()).orElseThrow();
        internshipService.assign(internship2.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(5), LocalDate.now().plusDays(60), "gap2"), hrPrincipal);

        // Staff-created group (supervisor = moderator via creator bypass)
        MvcResult groupRes = mockMvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateGroupRequest("gap-group", List.of(internUser.getId(), secondInternUser.getId())))))
                .andExpect(status().isCreated())
                .andReturn();
        groupId = UUID.fromString(objectMapper.readTree(groupRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult listRes = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode c : objectMapper.readTree(listRes.getResponse().getContentAsString())) {
            if (c.get("type").asText().equals("PRIVATE")) {
                privateConversationId = UUID.fromString(c.get("id").asText());
            }
        }
        assertThat(privateConversationId).isNotNull();
    }

    @Test
    @DisplayName("Revoke mode: completed intern loses GROUP access but keeps PRIVATE history")
    void completedInternLosesGroupKeepsPrivate() throws Exception {
        mockMvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("before completion"))))
                .andExpect(status().isCreated());

        internship.setStatus(InternshipStatus.COMPLETED);
        ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository).save(internship);

        // GROUP history blocked, GROUP absent from list
        mockMvc.perform(get("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());

        MvcResult list = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode c : objectMapper.readTree(list.getResponse().getContentAsString())) {
            assertThat(c.get("id").asText()).isNotEqualTo(groupId.toString());
        }

        // PRIVATE thread history survives completion
        mockMvc.perform(get("/api/conversations/" + privateConversationId + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        // Unaffected parties keep GROUP access (active intern + staff moderator)
        mockMvc.perform(get("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + secondInternToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Revoke mode: completed leaver cannot rejoin a group")
    void completedLeaverCannotRejoin() throws Exception {
        internship.setStatus(InternshipStatus.COMPLETED);
        ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository).save(internship);

        // Relinquishing access is always allowed, even when revoked
        mockMvc.perform(post("/api/conversations/" + groupId + "/leave")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isNoContent());

        // Direct re-add attempt by the moderator is rejected on standing
        mockMvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddMemberRequest(internUser.getId()))))
                .andExpect(status().isUnprocessableEntity());
    }
}
