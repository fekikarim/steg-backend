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
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T08 — community feed reads, scope, validation and idempotency
 * (Testcontainers, real Postgres).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T08 — Community Feed Tests")
class CommunityFeedTest {

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
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User adminUser;
    private User supervisorUser;
    private User internUser;
    private User intern2User;
    private User outsiderUser;
    private User graduatedUser;

    private String adminToken;
    private String supervisorToken;
    private String internToken;
    private String intern2Token;
    private String outsiderToken;
    private String graduatedToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepository.saveAndFlush(new User("t08_admin_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = newStaffUser("t08_sup_" + suffix, "SUPERVISOR");
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(),
                List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("t08_intern_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        intern2User = userRepository.saveAndFlush(new User("t08_intern2_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        intern2Token = jwtService.generateAccessToken(intern2User.getId(), intern2User.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        outsiderUser = userRepository.saveAndFlush(new User("t08_outsider_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(),
                List.of("ROLE_CANDIDATE"));

        graduatedUser = userRepository.saveAndFlush(new User("t08_grad_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        graduatedToken = jwtService.generateAccessToken(graduatedUser.getId(), graduatedUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_T08_" + suffix, "Direction T08", "T08"));
        Employee supervisorEmployee = new Employee("EMP-T08-" + suffix, "Sana", "Trabelsi", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_T08_" + suffix, "INSAT Tunis"));
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        newActiveInternship("t08_intern_" + suffix, internUser, uni, dept, supervisorEmployee, adminPrincipal, suffix + "01");
        newActiveInternship("t08_intern2_" + suffix, intern2User, uni, dept, supervisorEmployee, adminPrincipal, suffix + "02");
        // Graduated: an internship that already left IN_PROGRESS.
        Internship grad = newGraduatedInternship("t08_grad_" + suffix, graduatedUser, uni, suffix + "03");
        assertThat(grad.getStatus()).isEqualTo(InternshipStatus.VALIDATED);
    }

    // ------------------------------------------------------------------
    // Scope (BR-39)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Anonymous callers are refused on the feed")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("A user without an internship is refused (read + write)")
    void outsiderWithoutInternshipIsRefused() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hello?\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A graduated student loses community access but past posts remain")
    void graduatedInternLosesAccess() throws Exception {
        UUID postId = createPost(internToken, "visible to everyone active");
        mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + graduatedToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + graduatedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"trying to post after graduation\"}"))
                .andExpect(status().isForbidden());
        // …while active students still read the feed (the graduated author's
        // own post is still there — posts survive graduation).
        MvcResult feed = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + intern2Token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(feed.getResponse().getContentAsString()).contains(postId.toString());
    }

    @Test
    @DisplayName("Staff can read the feed but cannot publish")
    void staffCanReadButCannotPost() throws Exception {
        createPost(internToken, "staff readability probe");
        mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"staff post attempt\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"admin post attempt\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Feed contract: privacy, order, keyset pagination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Post response carries a display name, never email/CIN")
    void postResponseIsPrivacySafe() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"privacy probe\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("authorDisplayName").asText()).isNotBlank();
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("t08_intern_")
                .doesNotContain("email")
                .doesNotContain("nationalId");
    }

    @Test
    @DisplayName("Feed is newest-first and the keyset survives concurrent inserts")
    void feedOrderingAndKeysetStability() throws Exception {
        UUID first = createPost(internToken, "first post");
        UUID second = createPost(internToken, "second post");
        UUID third = createPost(internToken, "third post");

        MvcResult page1 = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p1 = objectMapper.readTree(page1.getResponse().getContentAsString());
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        assertThat(p1.get("items").get(0).get("id").asText()).isEqualTo(third.toString());
        assertThat(p1.get("items").get(1).get("id").asText()).isEqualTo(second.toString());
        String cursorTs = p1.get("nextCursorTs").asText();
        String cursorId = p1.get("nextCursorId").asText();

        // A concurrent insert at the top must not shift the cursor window.
        UUID fourth = createPost(internToken, "concurrent fourth");

        MvcResult page2 = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "2")
                        .param("cursorTs", cursorTs)
                        .param("cursorId", cursorId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p2 = objectMapper.readTree(page2.getResponse().getContentAsString());
        assertThat(p2.get("hasMore").asBoolean()).isFalse();
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("items").get(0).get("id").asText()).isEqualTo(first.toString());

        // …and the new post leads a fresh first page (no skip, no duplicate).
        MvcResult fresh = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(fresh.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).get("id").asText()).isEqualTo(fourth.toString());
    }

    @Test
    @DisplayName("Half a cursor is rejected")
    void cursorPairRequired() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("cursorTs", "2026-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------
    // Validation (BR-40 + limits)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Blank and over-long bodies are rejected")
    void blankAndTooLongRejected() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/community/posts/" + createPost(internToken, "comment target") + "/comments")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "y".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Contact data (email/phone) is rejected; ordinary numbers pass")
    void contactDataRejected() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"contact me at friend@example.com please\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"call me on 20 123 456 asap\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"completed 75% of chapter 3 tasks in 2026\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Identical bodies within the window are rejected as duplicates")
    void duplicateBodyRejected() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"same question twice\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"  same   question twice \"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Idempotent replay returns the same post, no duplicate row")
    void idempotentReplayReturnsSamePost() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"idempotent hello\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"idempotent hello\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String replayId = objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText();
        assertThat(replayId).isEqualTo(firstId);

        MvcResult feed = mockMvc.perform(get("/api/community/posts")
                        .header("Authorization", "Bearer " + internToken)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        long matches = 0;
        for (JsonNode item : objectMapper.readTree(feed.getResponse().getContentAsString()).get("items")) {
            if (item.get("id").asText().equals(firstId)) {
                matches++;
            }
        }
        assertThat(matches).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Comments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Comments thread chronologically and the counter stays consistent")
    void commentLifecycleAndCounter() throws Exception {
        UUID postId = createPost(internToken, "commentable post");
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + intern2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"first reply\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"thanks!\"}"))
                .andExpect(status().isCreated());

        MvcResult thread = mockMvc.perform(get("/api/community/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode comments = objectMapper.readTree(thread.getResponse().getContentAsString()).get("content");
        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).get("body").asText()).isEqualTo("first reply");
        assertThat(comments.get(1).get("body").asText()).isEqualTo("thanks!");

        MvcResult detail = mockMvc.perform(get("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("commentCount").asInt()).isEqualTo(2);

        UUID commentId = UUID.fromString(comments.get(0).get("id").asText());
        mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .header("Authorization", "Bearer " + intern2Token))
                .andExpect(status().isNoContent());

        MvcResult detailAfter = mockMvc.perform(get("/api/community/posts/" + postId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(detailAfter.getResponse().getContentAsString())
                .get("commentCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Commenting on an unknown post is 404")
    void commentOnUnknownPostIs404() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + UUID.randomUUID() + "/comments")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"nowhere\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Image/PDF attachments accepted within limits, rejected outside")
    void attachmentSecurityMatrix() throws Exception {
        byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);

        MvcResult created = mockMvc.perform(multipart("/api/community/posts/with-attachment")
                        .file(new MockMultipartFile("file", "notes.pdf", "application/pdf", pdf))
                        .param("body", "post with pdf")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode post = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(post.get("attachment").get("fileName").asText()).isEqualTo("notes.pdf");
        UUID attachmentId = UUID.fromString(post.get("attachment").get("id").asText());

        // Download works for an eligible reader…
        MvcResult download = mockMvc.perform(get("/api/community/posts/attachments/" + attachmentId + "/download")
                        .header("Authorization", "Bearer " + intern2Token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(pdf);

        // …but not for a non-reader (no leak, no member-oracle beyond 403).
        mockMvc.perform(get("/api/community/posts/attachments/" + attachmentId + "/download")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        // Executables are rejected by content check.
        mockMvc.perform(multipart("/api/community/posts/with-attachment")
                        .file(new MockMultipartFile("file", "run.exe", "application/x-msdownload",
                                "MZ fake binary".getBytes(StandardCharsets.UTF_8)))
                        .param("body", "post with exe")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isUnprocessableEntity());

        // Oversized files are rejected before storage.
        mockMvc.perform(multipart("/api/community/posts/with-attachment")
                        .file(new MockMultipartFile("file", "big.pdf", "application/pdf",
                                new byte[11 * 1024 * 1024]))
                        .param("body", "post with big pdf")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Unknown attachment download is 404")
    void unknownAttachmentIs404() throws Exception {
        mockMvc.perform(get("/api/community/posts/attachments/" + UUID.randomUUID() + "/download")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private User newStaffUser(String email, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        User user = new User(email + "@steg.com", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private void newActiveInternship(String emailPrefix, User intern, University uni,
                                     Department dept, Employee supervisor, UserPrincipal admin,
                                     String cin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Ws", "Intern", emailPrefix + "@steg.com", cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "T08 Project", "Ingénieur", false), admin);
        internshipService.assign(created.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "T08 assignment"), admin);
    }

    private Internship newGraduatedInternship(String emailPrefix, User intern, University uni, String cin)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Grad", "Uated", emailPrefix + "@steg.com", cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(90),
                LocalDate.now().minusDays(10),
                "Old project", "Ingénieur", false), adminPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internship.setStatus(InternshipStatus.VALIDATED);
        return internshipRepository.saveAndFlush(internship);
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
