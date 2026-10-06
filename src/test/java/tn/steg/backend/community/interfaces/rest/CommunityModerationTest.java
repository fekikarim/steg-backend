package tn.steg.backend.community.interfaces.rest;

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
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T08 / D7 — community moderation: author deletes, staff removals (reason
 * mandatory), reports, mutes, audit trail and author notifications
 * (Testcontainers, real Postgres).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T08 — Community Moderation Tests")
class CommunityModerationTest {

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
    private InternshipService internshipService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User adminUser;
    private User supervisorUser;
    private User internUser;
    private User intern2User;

    private String adminToken;
    private String supervisorToken;
    private String internToken;
    private String intern2Token;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepository.saveAndFlush(new User("t08m_admin_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        Role supRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();
        supervisorUser = new User("t08m_sup_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE);
        supervisorUser.getAssignedRoles().add(supRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(),
                List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("t08m_intern_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        intern2User = userRepository.saveAndFlush(new User("t08m_intern2_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        intern2Token = jwtService.generateAccessToken(intern2User.getId(), intern2User.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_T08M_" + suffix, "Direction T08M", "T08M"));
        Employee supervisorEmployee = new Employee("EMP-T08M-" + suffix, "Sana", "Trabelsi", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_T08M_" + suffix, "INSAT Tunis"));
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        newActiveInternship("t08m_intern_" + suffix, internUser, uni, dept, supervisorEmployee, adminPrincipal, suffix + "11");
        newActiveInternship("t08m_intern2_" + suffix, intern2User, uni, dept, supervisorEmployee, adminPrincipal, suffix + "22");
    }

    // ------------------------------------------------------------------
    // Deletes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Author deletes own post: withdrawn, gone from feed, 404 on read")
    void authorDeletesOwnPost() throws Exception {
        UUID postId = createPost(internToken, "withdrawn post");
        mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        MvcResult feed = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(feed.getResponse().getContentAsString()).doesNotContain(postId.toString());

        mockMvc.perform(get("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A non-author student cannot delete another post")
    void nonAuthorCannotDelete() throws Exception {
        UUID postId = createPost(internToken, "not yours");
        mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + intern2Token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Staff removal needs a reason and redacts the body")
    void staffRemovalNeedsReasonAndRedacts() throws Exception {
        UUID postId = createPost(internToken, "offending content here");

        mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isUnprocessableEntity());

        MvcResult removed = mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("reason", "inappropriate language"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(removed.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("REMOVED");
        assertThat(body.get("body").asText()).doesNotContain("offending content");

        // Gone from the feed and from single reads (author notification is
        // proven in CommunityNotificationTest — BEFORE_COMMIT listeners only
        // fire on commit, never inside a rollback test).
        MvcResult feed = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + intern2Token)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(feed.getResponse().getContentAsString()).doesNotContain(postId.toString());
        mockMvc.perform(get("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Staff comment removal decrements the counter")
    void staffCommentRemovalDecrementsCounter() throws Exception {
        UUID postId = createPost(internToken, "thread with bad reply");
        MvcResult comment = mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"bad reply\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID commentId = UUID.fromString(
                objectMapper.readTree(comment.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("reason", "spam"))
                .andExpect(status().isNoContent());

        MvcResult thread = mockMvc.perform(get("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(thread.getResponse().getContentAsString())
                .get("content")).hasSize(0);

        MvcResult detail = mockMvc.perform(get("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("commentCount").asInt()).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Report flow: create, duplicate refused, staff queue, resolve")
    void reportFlow() throws Exception {
        UUID postId = createPost(internToken, "reportable post");

        MvcResult report = mockMvc.perform(post("/api/community/reports")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"postId\":\"" + postId + "\",\"reason\":\"spam\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID reportId = UUID.fromString(
                objectMapper.readTree(report.getResponse().getContentAsString()).get("id").asText());

        // One open report per reporter and target.
        mockMvc.perform(post("/api/community/reports")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"postId\":\"" + postId + "\",\"reason\":\"spam again\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Reporting your own content is refused.
        mockMvc.perform(post("/api/community/reports")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"postId\":\"" + postId + "\",\"reason\":\"mine\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Staff queue shows it (reporter id staff-visible)…
        MvcResult queue = mockMvc.perform(get("/api/community/moderation/reports")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(queue.getResponse().getContentAsString()).contains(reportId.toString());

        // …students cannot open the queue.
        mockMvc.perform(get("/api/community/moderation/reports")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());

        // Resolve is terminal and idempotent.
        mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/resolve")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"post removed\"}"))
                .andExpect(status().isOk());
        MvcResult resolved = mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/resolve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(resolved.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("RESOLVED");
    }

    // ------------------------------------------------------------------
    // Mutes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Muted student cannot write until expiry; staff cannot be muted")
    void muteFlow() throws Exception {
        mockMvc.perform(post("/api/community/moderation/mutes")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + intern2User.getId() + "\",\"minutes\":60,\"reason\":\"repeated spam\"}"))
                .andExpect(status().isCreated());

        MvcResult refused = mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"muted attempt\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        String payload = refused.getResponse().getContentAsString();
        assertThat(payload).contains("STUDENT_MUTED");
        assertThat(payload).contains("repeated spam");
        // Moderator identity never leaks into the refusal.
        assertThat(payload).doesNotContain(supervisorUser.getEmail());

        UUID postId = createPost(internToken, "comment target");
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"muted comment\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Staff accounts cannot be muted; bounds are validated.
        mockMvc.perform(post("/api/community/moderation/mutes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + supervisorUser.getId() + "\",\"minutes\":60,\"reason\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/community/moderation/mutes")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + intern2User.getId() + "\",\"minutes\":1,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());

        // Lifting restores write access.
        mockMvc.perform(delete("/api/community/moderation/mutes/" + intern2User.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"back after unmute\"}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Audit (BR-39: every moderation action audited, source MOBILE)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Moderation actions are audited with MOBILE source")
    void moderationActionsAudited() throws Exception {
        UUID postId = createPost(internToken, "to be moderated");
        mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("reason", "audit probe"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/community/moderation/mutes")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + intern2User.getId() + "\",\"minutes\":60,\"reason\":\"audit probe\"}"))
                .andExpect(status().isCreated());

        List<AuditLog> removals = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("CommunityPost", postId);
        assertThat(removals).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("COMMUNITY_POST_REMOVED");
            assertThat(log.getSource()).isEqualTo(AuditSource.MOBILE);
        });

        List<AuditLog> creations = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("CommunityPost", postId);
        assertThat(creations).anySatisfy(log ->
                assertThat(log.getAction()).isEqualTo("COMMUNITY_POST_CREATED"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void newActiveInternship(String emailPrefix, User intern, University uni,
                                     Department dept, Employee supervisor, UserPrincipal admin,
                                     String cin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Mod", "Erator", emailPrefix + "@steg.com", cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "T08M Project", "Ingénieur", false), admin);
        internshipService.assign(created.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "T08M assignment"), admin);
    }

    private UUID createPost(String bearer, String body) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }
}
