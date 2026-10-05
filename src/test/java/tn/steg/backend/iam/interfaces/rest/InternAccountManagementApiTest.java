package tn.steg.backend.iam.interfaces.rest;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

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

/**
 * 'STEG intern' account management contract (AGENTS.md §5.4 + §5 list rule),
 * proven against a real Postgres through Testcontainers.
 *
 * <p>The paged list is server-side (search/filter/sort/page clamp), creation
 * and password reset return the one-time secret exactly once with
 * {@code Cache-Control: no-store}, a Supervisor account created here is the
 * <b>same</b> user row that Supervisor Management operates on (no duplicate
 * identity), and deletion is refused with 409 while the supervisor still has
 * active assignments.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1 — Intern accounts workspace (AGENTS.md §5.4)")
class InternAccountManagementApiTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private MockMvc mockMvc;

    private String run;
    private String adminToken;
    private String supervisorToken;

    private User internLow;
    private User internHighA;
    private User internHighB;
    private User supervisorAccount;
    private Department department;
    private University university;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        adminToken = token(newUser("admin", "ADMIN"), "ROLE_ADMIN");
        // Distinct salt so this scope-probe account never matches `q=run`.
        String probeSalt = UUID.randomUUID().toString();
        supervisorToken = token(newUser("probe", "SUPERVISOR", probeSalt), "ROLE_SUPERVISOR");

        // Emails all contain `run`, so `q=run` isolates this fixture.
        internLow = newUser("intern_a", "INTERN");          // email sorts first
        internHighA = newUser("intern_z1", "INTERN");
        internHighB = newUser("intern_z2", "INTERN");
        supervisorAccount = newUser("intern_sup", "SUPERVISOR");

        department = departmentRepository.saveAndFlush(
                new Department("DEPT_INT_ACC_" + run, "Dept Int Acc " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_INT_ACC_" + run, "Uni Int Acc " + run));
    }

    // =========================================================================
    // Server-side list (§5 list rule)
    // =========================================================================

    @Test
    @DisplayName("GET /manage is paged, filtered and sorted server-side")
    void manageListIsPagedAndFilteredServerSide() throws Exception {
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].email").value(internLow.getEmail()))
                .andExpect(jsonPath("$.content[0].roles[0]").value("INTERN"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));

        // Role filter narrows to the Supervisor account only.
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("role", "SUPERVISOR")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(supervisorAccount.getEmail()));

        // Status filter (all ACTIVE except the one we lock).
        supervisorAccount.setStatus(UserStatus.LOCKED);
        userRepository.saveAndFlush(supervisorAccount);
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("status", "LOCKED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(supervisorAccount.getEmail()));
    }

    @Test
    @DisplayName("search matches the candidate full name and never drops a candidate-less supervisor (LEFT JOIN)")
    void searchMatchesFullNameWithoutDroppingSupervisors() throws Exception {
        Candidate candidate = newCandidate("Zinedine" + run, "Zidane" + run);
        candidate.setUser(internHighA);
        candidateRepository.saveAndFlush(candidate);

        // Full-name search finds the student account.
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", "zinedine" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(internHighA.getEmail()))
                .andExpect(jsonPath("$.content[0].fullName").value("Zinedine" + run + " Zidane" + run));

        // A supervisor account has no candidate: the LEFT join must keep it.
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", supervisorAccount.getEmail())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(supervisorAccount.getEmail()));
    }

    @Test
    @DisplayName("sort is whitelisted and the page size is clamped")
    void sortIsWhitelistedAndSizeClamped() throws Exception {
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("sort", "email,desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value(internHighB.getEmail()));

        // Not whitelisted: silently falls back to email ASC instead of failing.
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("sort", "passwordHash,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].email").value(internLow.getEmail()));

        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", run)
                        .param("size", "5000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    @DisplayName("GET /manage is Admin-only and the literal segment is not swallowed by /{id}")
    void manageRouteIsAdminOnlyAndNotSwallowedById() throws Exception {
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/intern-accounts/manage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").exists());

        // The neighbouring /{id} route still parses a real UUID.
        mockMvc.perform(get("/api/intern-accounts/" + internLow.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(internLow.getEmail()));
    }

    // =========================================================================
    // Create / reset / status / delete
    // =========================================================================

    @Test
    @DisplayName("creating an account returns the one-time password with no-store and mustChangePassword")
    void createAccountReturnsOneTimeCredentials() throws Exception {
        String email = "created_intern_" + run + "@steg.tn";

        var response = mockMvc.perform(post("/api/intern-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", email, "role", "INTERN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.emailSent").exists())
                .andReturn().getResponse();

        assertThat(response.getHeader("Cache-Control")).contains("no-store");

        String plaintext = objectMapper.readTree(response.getContentAsString())
                .get("temporaryPassword").asText();
        User created = userRepository.findByEmail(email).orElseThrow();
        assertThat(created.getMustChangePassword()).isTrue();
        assertThat(created.getPasswordHash()).isNotBlank().isNotEqualTo(plaintext);

        // The new account shows up in the server-side list.
        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", email)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].roles[0]").value("INTERN"));
    }

    @Test
    @DisplayName("a Supervisor account is ONE identity shared with Supervisor Management (no duplicate row)")
    void supervisorAccountIsSharedWithSupervisorManagement() throws Exception {
        String email = "shared_sup_" + run + "@steg.tn";

        mockMvc.perform(post("/api/intern-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", email, "role", "SUPERVISOR"))))
                .andExpect(status().isCreated());

        String accountId = userRepository.findByEmail(email).orElseThrow().getId().toString();

        mockMvc.perform(get("/api/intern-accounts/manage")
                        .param("q", email)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(accountId));

        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", email)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(accountId));
    }

    @Test
    @DisplayName("reset password generates a NEW one-time secret, shows it once, never caches it")
    void resetPasswordRegeneratesTheSecretOnce() throws Exception {
        String before = userPort().findById(internLow.getId()).orElseThrow().getPasswordHash();

        var response = mockMvc.perform(post("/api/intern-accounts/" + internLow.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(internLow.getEmail()))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn().getResponse();

        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String plaintext = objectMapper.readTree(response.getContentAsString())
                .get("temporaryPassword").asText();
        User reloaded = userPort().findById(internLow.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotBlank().isNotEqualTo(before).isNotEqualTo(plaintext);
        assertThat(reloaded.getMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("activate/deactivate flips enabled and status without touching the password")
    void activateAndDeactivate() throws Exception {
        String hashBefore = userPort().findById(internHighB.getId()).orElseThrow().getPasswordHash();

        mockMvc.perform(patch("/api/intern-accounts/" + internHighB.getId() + "/status")
                        .param("enabled", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(patch("/api/intern-accounts/" + internHighB.getId() + "/status")
                        .param("status", "INACTIVE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(userPort().findById(internHighB.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("deleting an assigned supervisor is refused with 409; deleting an intern unlinks the candidate")
    void deleteGuardsAssignmentsAndUnlinksCandidate() throws Exception {
        // A supervisor with an ACTIVE user-backed assignment cannot be deleted.
        Internship internship = newInternship("INT-ACC-DEL-" + run);
        assignmentPort().saveAndFlush(activeAssignment(internship, supervisorAccount));

        mockMvc.perform(delete("/api/intern-accounts/" + supervisorAccount.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS"));
        assertThat(userPort().findById(supervisorAccount.getId())).isPresent();

        // An intern account linked to a candidate deletes and unlinks the profile.
        Candidate candidate = newCandidate("Unlink" + run, "Me" + run);
        candidate.setUser(internHighA);
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(delete("/api/intern-accounts/" + internHighA.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(userPort().findById(internHighA.getId())).isEmpty();
        assertThat(candidatePort().findById(candidate.getId()).orElseThrow().getUser()).isNull();
    }

    @Test
    @DisplayName("delete requires the Admin role")
    void deleteIsAdminOnly() throws Exception {
        mockMvc.perform(delete("/api/intern-accounts/" + internHighB.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }

    /** Domain-port views: the infrastructure repositories implement both the JPA
     *  interface and the domain port, so findById is ambiguous without a cast. */
    private tn.steg.backend.iam.domain.repository.UserRepository userPort() {
        return userRepository;
    }

    private tn.steg.backend.candidate.domain.repository.CandidateRepository candidatePort() {
        return candidateRepository;
    }

    private User newUser(String prefix, String roleCode) {
        return newUser(prefix, roleCode, run);
    }

    private User newUser(String prefix, String roleCode, String salt) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_" + salt + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Candidate newCandidate(String firstName, String lastName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest((firstName + lastName + run).getBytes(StandardCharsets.UTF_8)));
            Candidate candidate = new Candidate(firstName, lastName,
                    "cand_" + firstName.toLowerCase() + "_" + run + "@steg.tn", cinHash, university);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Internship newInternship(String reference) {
        Candidate candidate = newCandidate("Int" + reference, "Acc");
        Internship internship = new Internship(reference, candidate,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.APPROVED);
        return internshipRepository.saveAndFlush(internship);
    }

    private InternshipAssignment activeAssignment(Internship internship, User supervisorUser) {
        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        assignment.setSupervisorUser(supervisorUser);
        assignment.setAssignedByUser(supervisorUser);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        return assignment;
    }
}
