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
 * T08 — community notification fan-out (Testcontainers, real Postgres).
 *
 * <p>Deliberately NOT {@code @Transactional}: the fan-out runs in
 * {@code BEFORE_COMMIT} listeners, which only fire when the publishing
 * transaction commits — a rollback test would observe nothing. Fixtures use
 * random suffixes per test so committed rows never collide.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T08 — Community Notification Tests")
class CommunityNotificationTest {

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
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User supervisorUser;
    private User internUser;
    private User intern2User;

    private String supervisorToken;
    private String internToken;
    private String intern2Token;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User adminUser = userRepository.saveAndFlush(new User("t08n_admin_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        Role supRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();
        supervisorUser = new User("t08n_sup_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE);
        supervisorUser.getAssignedRoles().add(supRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(),
                List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("t08n_intern_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        intern2User = userRepository.saveAndFlush(new User("t08n_intern2_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        intern2Token = jwtService.generateAccessToken(intern2User.getId(), intern2User.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_T08N_" + suffix, "Direction T08N", "T08N"));
        Employee supervisorEmployee = new Employee("EMP-T08N-" + suffix, "Sana", "Trabelsi", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_T08N_" + suffix, "INSAT Tunis"));
        newActiveInternship("t08n_intern_" + suffix, internUser, uni, dept, supervisorEmployee, adminPrincipal, suffix + "51");
        newActiveInternship("t08n_intern2_" + suffix, intern2User, uni, dept, supervisorEmployee, adminPrincipal, suffix + "52");
    }

    @Test
    @DisplayName("Commenting notifies the post author with a post deep link (no self-notify)")
    void commentNotifiesPostAuthor() throws Exception {
        UUID postId = createPost(internToken, "please reply here");
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"here to help\"}"))
                .andExpect(status().isCreated());

        String payload = notifications(internToken);
        assertThat(payload).contains("COMMUNITY_COMMENT");
        assertThat(payload).contains(postId.toString());
        // Commenter named by display name, never by email.
        assertThat(payload).doesNotContain("t08n_intern2_");

        // A self-comment notifies nobody: still exactly one COMMUNITY_COMMENT.
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"self note\"}"))
                .andExpect(status().isCreated());
        assertThat(countOccurrences(notifications(internToken), "COMMUNITY_COMMENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("Post removal notifies the author with the reason, never the moderator")
    void postRemovalNotifiesAuthor() throws Exception {
        UUID postId = createPost(internToken, "offending content here");
        mockMvc.perform(delete("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("reason", "inappropriate language"))
                .andExpect(status().isOk());

        String payload = notifications(internToken);
        assertThat(payload).contains("COMMUNITY_POST_REMOVED");
        assertThat(payload).contains("inappropriate language");
        assertThat(payload).doesNotContain(supervisorUser.getEmail());
    }

    @Test
    @DisplayName("Comment removal notifies the comment author")
    void commentRemovalNotifiesAuthor() throws Exception {
        UUID postId = createPost(internToken, "thread for removal");
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"bad reply\"}"))
                .andExpect(status().isCreated());

        MvcResult thread = mockMvc.perform(get("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(objectMapper
                .readTree(thread.getResponse().getContentAsString()).get("content").get(0).get("id").asText());

        mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .param("reason", "spam"))
                .andExpect(status().isNoContent());

        assertThat(notifications(intern2Token)).contains("COMMUNITY_COMMENT_REMOVED");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void newActiveInternship(String emailPrefix, User intern, University uni,
                                     Department dept, Employee supervisor, UserPrincipal admin,
                                     String cin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Notif", "Ication", emailPrefix + "@steg.com", cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "T08N Project", "Ingénieur", false), admin);
        internshipService.assign(created.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "T08N assignment"), admin);
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

    private String notifications(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
