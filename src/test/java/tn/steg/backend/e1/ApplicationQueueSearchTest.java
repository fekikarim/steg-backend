package tn.steg.backend.e1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff application queue contract (AGENTS.md §5.2 "Application list ... with
 * filters, search, pagination"; §6.2 "Supervisor: own candidates").
 *
 * <p>The list screen rule (AGENTS.md §5) requires pagination, search, filters
 * and sort to run SERVER-SIDE. This test pins:
 * <ul>
 *   <li>paging metadata and ordering (page/size/totalPages),</li>
 *   <li>search over reference, candidate name and candidate email,</li>
 *   <li>filters: status, internship type, university, submission date range,</li>
 *   <li>role scope: ADMIN sees everything, SUPERVISOR only his own candidates,
 *       CANDIDATE is refused (403),</li>
 *   <li>sort sanitization (unknown property falls back, size is clamped).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1 — Staff application queue: paging, search, filters, scope")
class ApplicationQueueSearchTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;

    private MockMvc mockMvc;

    /** Unique per test method so parallel/previous rows never pollute assertions. */
    private String run;
    private String adminToken;
    private String supervisorToken;
    private String candidateToken;
    private Candidate candidateB;
    private University universityA;
    private String universityAName;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        User admin = newUser("admin", "ADMIN");
        adminToken = token(admin, "ROLE_ADMIN");
        User supervisor = newUser("sup", "SUPERVISOR");
        supervisorToken = token(supervisor, "ROLE_SUPERVISOR");
        User candidate = newUser("cand", "CANDIDATE");
        candidateToken = token(candidate, "ROLE_CANDIDATE");

        universityA = universityRepository.saveAndFlush(
                new University("UNI_QA_" + run, "Queue University A " + run));
        University universityB = universityRepository.saveAndFlush(
                new University("UNI_QB_" + run, "Queue University B " + run));
        universityAName = universityA.getName();

        // One candidate per application: the DB enforces a single active
        // application per candidate (`uq_applications_candidate_id_active`).
        Candidate candidateA1 = newCandidate("A1", "Amine", universityA);
        Candidate candidateA2 = newCandidate("A2", "Ayoub", universityA);
        candidateB = newCandidate("B1", "Bilel", universityB);
        Candidate candidateC1 = newCandidate("C1", "Chiheb", universityA);

        // A1 SUBMITTED, type PFE, submitted 3 days ago
        saveApp("A1", candidateA1, ApplicationStatus.SUBMITTED, InternshipType.PFE, LocalDate.now().minusDays(3));
        // A2 RESUBMITTED, type PERFECTIONNEMENT, submitted yesterday
        saveApp("A2", candidateA2, ApplicationStatus.RESUBMITTED, InternshipType.PERFECTIONNEMENT, LocalDate.now().minusDays(1));
        // B1 REJECTED with a stored reason, type PFE, submitted today
        saveApp("B1", candidateB, ApplicationStatus.REJECTED, InternshipType.PFE, LocalDate.now())
                .setRejectionReason("dossier incomplet");
        // C1 DRAFT, no submission date at all
        saveApp("C1", candidateC1, ApplicationStatus.DRAFT, null, null);

        // The supervisor supervises the two candidates of A1 and A2 only
        // (user-backed supervision, the only model since V35/V36).
        for (Candidate supervised : List.of(candidateA1, candidateA2)) {
            Internship internship = new Internship(
                    "INT-Q-" + supervised.getLastName(), supervised,
                    LocalDate.now(), LocalDate.now().plusMonths(2),
                    InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
            internship.setSupervisorUser(supervisor);
            internshipRepository.saveAndFlush(internship);
        }
    }

    // =========================================================================
    // Pagination, sort, row payload
    // =========================================================================

    @Test
    @DisplayName("Admin queue is paged and sorted server-side (page metadata + row payload)")
    void adminQueueIsPagedAndSorted() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "0")
                        .param("sort", "reference,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A1"))
                .andExpect(jsonPath("$.content[0].candidateName").value("Amine A1-" + run))
                .andExpect(jsonPath("$.content[0].universityName").value(universityAName))
                .andExpect(jsonPath("$.content[1].reference").value("APP-Q-" + run + "-A2"));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "1")
                        .param("sort", "reference,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-B1"))
                .andExpect(jsonPath("$.content[1].reference").value("APP-Q-" + run + "-C1"));
    }

    @Test
    @DisplayName("sort by candidate.lastName and submissionDate is honoured; unknown property falls back")
    void sortKeysAreHonouredAndSanitized() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("sort", "candidate.lastName,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].candidateName").value("Amine A1-" + run))
                .andExpect(jsonPath("$.content[3].candidateName").value("Chiheb C1-" + run));

        // Not in the whitelist: silently falls back to createdAt DESC instead of failing.
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("sort", "passwordHash,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // Oversized pages are clamped so a client cannot pull the whole table.
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("size", "500")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    // =========================================================================
    // Search
    // =========================================================================

    @Test
    @DisplayName("search matches reference fragment, candidate name and candidate email")
    void searchMatchesReferenceNameAndEmail() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", "APP-Q-" + run + "-B1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-B1"));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", "bilel b1-" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].candidateName").value("Bilel B1-" + run));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", "queue_c1_" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-C1"));
    }

    // =========================================================================
    // Filters
    // =========================================================================

    @Test
    @DisplayName("status, type and university filters narrow the queue")
    void statusTypeAndUniversityFilters() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("status", "SUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A1"));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("type", "PERFECTIONNEMENT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A2"));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("university", universityAName)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3));

        // Filters combine (AND): the university filter plus status.
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("university", universityAName)
                        .param("status", "RESUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A2"));
    }

    @Test
    @DisplayName("submission date range is inclusive and excludes DRAFT (no submission date)")
    void submissionDateRangeFilter() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("from", LocalDate.now().minusDays(2).toString())
                        .param("to", LocalDate.now().minusDays(1).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A2"));

        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("from", LocalDate.now().minusDays(3).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    // =========================================================================
    // Scope
    // =========================================================================

    @Test
    @DisplayName("Supervisor sees only his own candidates, whatever the client sends")
    void supervisorIsScopedToOwnCandidates() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("sort", "reference,asc")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reference").value("APP-Q-" + run + "-A1"))
                .andExpect(jsonPath("$.content[1].reference").value("APP-Q-" + run + "-A2"));

        // A client-supplied scope hint must be ignored: the server re-derives it.
        mockMvc.perform(get("/api/applications/manage")
                        .param("q", run)
                        .param("supervisorUserId", "00000000-0000-0000-0000-000000000000")
                        .param("candidateId", candidateB.getId().toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("Candidates are refused on the staff queue")
    void candidateCannotAccessStaffQueue() throws Exception {
        mockMvc.perform(get("/api/applications/manage")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User newUser(String prefix, String roleCode) {
        User user = userRepository.saveAndFlush(new User(
                prefix + "_q_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        if (role != null) {
            user.getAssignedRoles().add(role);
            user = userRepository.saveAndFlush(user);
        }
        return user;
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Candidate newCandidate(String label, String firstName, University university) {
        try {
            Candidate candidate = new Candidate(
                    firstName,
                    label + "-" + run,
                    "queue_" + label.toLowerCase() + "_" + run + "@steg.tn",
                    cinHash(label + "_" + run),
                    university);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cinHash(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private InternshipApplication saveApp(String label, Candidate candidate, ApplicationStatus status,
                                          InternshipType type, LocalDate submissionDate) {
        InternshipApplication app = new InternshipApplication(
                "APP-Q-" + run + "-" + label, candidate, status);
        app.setCalculatedType(type);
        app.setSubmissionDate(submissionDate);
        app.setSubmittedOnline(true);
        return applicationRepository.saveAndFlush(app);
    }
}
