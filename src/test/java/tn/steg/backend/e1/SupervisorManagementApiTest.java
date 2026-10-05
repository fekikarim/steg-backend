package tn.steg.backend.e1;

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
import tn.steg.backend.internship.application.dto.AssignCandidateRequest;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.application.dto.UpdateSupervisorRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supervisor Management workspace contract (AGENTS.md §5.6).
 *
 * <p>Pins the list screen rule (§5): the management list is paged, searchable
 * and filterable SERVER-SIDE on {@code GET /api/supervisors/manage}, the delete
 * of an assigned supervisor is refused with <b>409</b> so the UI can tell the
 * Admin to reassign first, assignment is refused with <b>409</b> unless the
 * candidate's application is approved, the reassignment endpoint moves the
 * supervision link, and the literal {@code /manage} path is not swallowed by
 * {@code /{id}}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E1 — Supervisor management workspace (AGENTS.md §5.6)")
class SupervisorManagementApiTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private MockMvc mockMvc;

    private String run;
    private String adminToken;
    private String supervisorToken;

    private User supervisorA;
    private User supervisorB;
    private User suspendedSupervisor;
    private Department department;
    private University university;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        User admin = newUser("admin", "ADMIN", UserStatus.ACTIVE);
        adminToken = token(admin, "ROLE_ADMIN");
        // Distinct salt so this scope-probe account never matches `q=run`.
        String scopeRun = UUID.randomUUID().toString();
        supervisorToken = token(newUser("scope", "SUPERVISOR", UserStatus.ACTIVE, scopeRun), "ROLE_SUPERVISOR");

        // Emails all contain `run`, so `q=run` isolates this fixture.
        supervisorA = newUser("supa", "SUPERVISOR", UserStatus.ACTIVE);
        supervisorB = newUser("supb", "SUPERVISOR", UserStatus.ACTIVE);
        suspendedSupervisor = newUser("sups", "SUPERVISOR", UserStatus.INACTIVE);

        department = departmentRepository.saveAndFlush(
                new Department("DEPT_SUP_MG_" + run, "Dept Sup MG " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_SUP_MG_" + run, "Uni Sup MG " + run));
    }

    // =========================================================================
    // List / search / filter / sort (§5 list screen rule)
    // =========================================================================

    @Test
    @DisplayName("GET /manage is paged and sorted server-side (row payload + page metadata)")
    void manageListIsPagedServerSide() throws Exception {
        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "0")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].email").value(supervisorA.getEmail()))
                .andExpect(jsonPath("$.content[0].role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].activeAssignmentsCount").value(0))
                .andExpect(jsonPath("$.content[0].id").value(supervisorA.getId().toString()));

        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "1")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(suspendedSupervisor.getEmail()));
    }

    @Test
    @DisplayName("search matches a fragment of the supervisor email; the status filter narrows it")
    void searchAndStatusFilterNarrowTheList() throws Exception {
        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", "supb_" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(supervisorB.getEmail()));

        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("status", "INACTIVE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(suspendedSupervisor.getEmail()));
    }

    @Test
    @DisplayName("sort is whitelisted and the page size is clamped")
    void sortIsWhitelistedAndSizeClamped() throws Exception {
        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("sort", "email,desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value(suspendedSupervisor.getEmail()));

        // Not whitelisted: silently falls back to email ASC instead of failing.
        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("sort", "passwordHash,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].email").value(supervisorA.getEmail()));

        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", run)
                        .param("size", "5000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    @DisplayName("the management list is Admin-only and the directory endpoint is unaffected")
    void manageListIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/supervisors/manage")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        // The selectable directory still lists the Admin (AGENTS.md §3.2).
        mockMvc.perform(get("/api/supervisors")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'ADMIN')]").exists());
    }

    // =========================================================================
    // Route table: /manage must not be captured by /{id}
    // =========================================================================

    @Test
    @DisplayName("/manage is served by the list endpoint, never by /{id} (no UUID parse failure)")
    void manageRouteIsNotSwallowedByIdPathVariable() throws Exception {
        mockMvc.perform(get("/api/supervisors/manage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.content").exists());

        // The neighbouring /{id} route still parses a real UUID.
        mockMvc.perform(get("/api/supervisors/" + supervisorA.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(supervisorA.getEmail()));
    }

    // =========================================================================
    // Create / edit / delete
    // =========================================================================

    @Test
    @DisplayName("creating a supervisor returns the one-time password with no-store and mustChangePassword")
    void createSupervisorReturnsOneTimeCredentials() throws Exception {
        String email = "supnew_" + run + "@steg.tn";

        var response = mockMvc.perform(post("/api/supervisors")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Cache-Control", "no-cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("email", email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.emailSent").exists())
                .andReturn().getResponse();

        // §5.6: credentials are shown once and never cached.
        assertThat(response.getHeader("Cache-Control")).contains("no-store");

        String plaintext = objectMapper.readTree(response.getContentAsString()).get("temporaryPassword").asText();
        User created = userRepository.findByEmail(email).orElseThrow();
        assertThat(created.getMustChangePassword()).isTrue();
        assertThat(created.getPasswordHash()).isNotBlank().isNotEqualTo(plaintext);

        // The new account shows up in the server-side list.
        mockMvc.perform(get("/api/supervisors/manage")
                        .param("q", email)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(email));
    }

    @Test
    @DisplayName("editing a supervisor updates status and enabled without touching other fields")
    void editSupervisorUpdatesStatusAndEnabled() throws Exception {
        mockMvc.perform(put("/api/supervisors/" + supervisorB.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateSupervisorRequest(UserStatus.INACTIVE, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.email").value(supervisorB.getEmail()));
    }

    @Test
    @DisplayName("deleting a supervisor with assigned candidates is refused with 409 so the UI can explain reassignment")
    void deleteWithAssignmentsReturns409() throws Exception {
        Internship internship = newInternship("INT-DEL-" + run, ApplicationStatus.APPROVED);
        internship.setSupervisorUser(supervisorA);
        internshipRepository.saveAndFlush(internship);

        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        assignment.setSupervisorUser(supervisorA);
        assignment.setAssignedByUser(supervisorA);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignmentPort().saveAndFlush(assignment);
        assertThat(assignmentPort().findBySupervisorUserIdAndStatus(supervisorA.getId(), AssignmentStatus.ACTIVE))
                .isNotEmpty();

        mockMvc.perform(delete("/api/supervisors/" + supervisorA.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Reassign")));

        // Still there: the account is not removed behind the Admin's back.
        assertThat(userPort().findById(supervisorA.getId())).isPresent();
    }

    @Test
    @DisplayName("deleting an unassigned supervisor succeeds and is refused for Supervisors")
    void deleteUnassignedSupervisorSucceeds() throws Exception {
        mockMvc.perform(delete("/api/supervisors/" + supervisorB.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        assertThat(userPort().findById(supervisorB.getId())).isEmpty();

        mockMvc.perform(delete("/api/supervisors/" + suspendedSupervisor.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Assign / reassign (§5.6)
    // =========================================================================

    @Test
    @DisplayName("assigning a candidate whose application is not approved is refused with 409 CANDIDATE_NOT_APPROVED")
    void assignRefusesNonApprovedCandidate() throws Exception {
        Internship pending = newInternship("INT-PEND-" + run, ApplicationStatus.SUBMITTED);

        mockMvc.perform(post("/api/supervisors/" + supervisorA.getId() + "/assign-candidate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignCandidateRequest(pending.getId(), department.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NOT_APPROVED"));

        assertThat(internshipPort().findById(pending.getId()).orElseThrow().getSupervisorUser()).isNull();
    }

    @Test
    @DisplayName("assigning an approved candidate creates the active assignment and moves the supervision link")
    void assignApprovedCandidateSucceeds() throws Exception {
        Internship approved = newInternship("INT-OK-" + run, ApplicationStatus.APPROVED);

        mockMvc.perform(post("/api/supervisors/" + supervisorA.getId() + "/assign-candidate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignCandidateRequest(approved.getId(), department.getId()))))
                .andExpect(status().isOk());

        Internship reloaded = internshipPort().findById(approved.getId()).orElseThrow();
        assertThat(reloaded.getSupervisorUser().getId()).isEqualTo(supervisorA.getId());
        assertThat(assignmentRepository.findByInternshipIdAndStatus(approved.getId(), AssignmentStatus.ACTIVE))
                .isPresent();
    }

    @Test
    @DisplayName("reassigning moves the internship to the new supervisor, keeps history and notifies both")
    void reassignMovesSupervisionAndKeepsHistory() throws Exception {
        Internship approved = newInternship("INT-RA-" + run, ApplicationStatus.APPROVED);
        approved.setSupervisorUser(supervisorA);
        internshipRepository.saveAndFlush(approved);

        InternshipAssignment first = new InternshipAssignment();
        first.setInternship(approved);
        first.setDestination(department);
        first.setSupervisorUser(supervisorA);
        first.setAssignedByUser(supervisorA);
        first.setAssignedAt(LocalDate.now());
        first.setStartDate(approved.getStartDate());
        first.setEndDate(approved.getEndDate());
        first.setStatus(AssignmentStatus.ACTIVE);
        final UUID firstAssignmentId = assignmentPort().saveAndFlush(first).getId();

        mockMvc.perform(put("/api/supervisors/" + supervisorA.getId() + "/reassign/" + approved.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReassignSupervisorRequest(supervisorB.getId(), "Supervisor on leave"))))
                .andExpect(status().isOk());

        assertThat(internshipPort().findById(approved.getId()).orElseThrow().getSupervisorUser().getId())
                .isEqualTo(supervisorB.getId());
        assertThat(assignmentPort().findByInternshipId(approved.getId()).stream()
                .filter(a -> a.getId().equals(firstAssignmentId))
                .findFirst().orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.REASSIGNED);
        assertThat(assignmentRepository.findByInternshipIdAndStatus(approved.getId(), AssignmentStatus.ACTIVE)
                .orElseThrow().getSupervisorUser().getId()).isEqualTo(supervisorB.getId());
    }

    @Test
    @DisplayName("assignment and reassignment are Admin-only")
    void assignmentCommandsAreAdminOnly() throws Exception {
        Internship approved = newInternship("INT-ADM-" + run, ApplicationStatus.APPROVED);

        mockMvc.perform(post("/api/supervisors/" + supervisorA.getId() + "/assign-candidate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignCandidateRequest(approved.getId(), department.getId()))))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The infrastructure repositories extend both JpaRepository and the domain
     *  port, so findById/saveAndFlush are ambiguous without an explicit view. */
    private tn.steg.backend.iam.domain.repository.UserRepository userPort() {
        return userRepository;
    }

    private tn.steg.backend.internship.domain.repository.InternshipRepository internshipPort() {
        return internshipRepository;
    }

    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }

    private User newUser(String prefix, String roleCode, UserStatus status) {
        return newUser(prefix, roleCode, status, run);
    }

    private User newUser(String prefix, String roleCode, UserStatus status, String salt) {
        User user = userRepository.saveAndFlush(
                new User(prefix + "_" + salt + "@steg.tn", "hash", status));
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

    private Internship newInternship(String reference, ApplicationStatus applicationStatus) {
        Candidate candidate = newCandidate(reference);
        InternshipApplication application = new InternshipApplication(
                "APP-" + reference, candidate, applicationStatus);
        application.setSubmissionDate(LocalDate.now());
        application = applicationRepository.saveAndFlush(application);

        Internship internship = new Internship(reference, candidate,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setApplication(application);
        // The internship row exists (created with the application); what the
        // assign command must refuse is a NOT approved application.
        internship.setStatus(InternshipStatus.APPROVED);
        return internshipRepository.saveAndFlush(internship);
    }

    private Candidate newCandidate(String label) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest((label + run).getBytes(StandardCharsets.UTF_8)));
            Candidate candidate = new Candidate(
                    "Candidat", label, "cand_" + label.toLowerCase() + "_" + run + "@steg.tn", cinHash, university);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}