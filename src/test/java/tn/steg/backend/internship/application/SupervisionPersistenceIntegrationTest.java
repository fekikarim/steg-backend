package tn.steg.backend.internship.application;

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
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.application.dto.SupervisorOption;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Persistence-backed proof for the supervision model (AGENTS.md §3).
 *
 * <p>The audit rows that depend on persistence (scope resolution, assignment
 * uniqueness, notification routing, the selectable directory) are proven here
 * against a real Postgres through Testcontainers — never with mocked
 * repositories. Every fixture uses the V36 <b>user-backed</b> assignment link
 * ({@code InternshipAssignment.supervisorUser}) with the legacy
 * {@code supervisor}/{@code assignedBy} employee links left NULL, which is the
 * exact production shape that the historical implicit-join bug silently
 * dropped.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Supervision persistence (Testcontainers): scope, routing, reassignment, directory")
class SupervisionPersistenceIntegrationTest {

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
    @Autowired private TaskRepository taskRepository;
    @Autowired private SupervisionScopeService supervisionScopeService;
    @Autowired private SupervisorManagementService supervisorManagementService;
    @Autowired private SupervisorDirectoryService supervisorDirectoryService;

    private MockMvc mockMvc;

    private String run;
    private User admin;
    private User supervisorA;
    private User supervisorB;
    private UserPrincipal adminPrincipal;
    private Department department;
    private University university;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        admin = newUser("admin", "ADMIN");
        supervisorA = newUser("supa", "SUPERVISOR");
        supervisorB = newUser("supb", "SUPERVISOR");
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        department = departmentRepository.saveAndFlush(
                new Department("DEPT_SUP_INT_" + run, "Dept Sup Int " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_SUP_INT_" + run, "Uni Sup Int " + run));
    }

    @Test
    @DisplayName("user-backed assignment alone grants scope and routes task notifications (legacy employee links NULL)")
    void userBackedAssignmentGrantsScopeAndRoutesNotifications() throws Exception {
        Internship internshipA = newInternship("INT-UB-A-" + run);
        // Only the user-backed link exists: no internship.supervisorUser, no legacy Employee.
        activeAssignment(internshipA, supervisorA);
        Internship internshipB = newInternship("INT-UB-B-" + run);
        activeAssignment(internshipB, supervisorB);

        Task taskA = taskRepository.saveAndFlush(new Task(internshipA, admin, "Task UB A " + run, "Desc"));
        Task taskB = taskRepository.saveAndFlush(new Task(internshipB, admin, "Task UB B " + run, "Desc"));

        // Scope resolves through the user-backed assignment, in the database.
        assertThat(supervisionScopeService.assignedInternships(principalOf(supervisorA, "SUPERVISOR")))
                .extracting(Internship::getId)
                .contains(internshipA.getId())
                .doesNotContain(internshipB.getId());
        assertThat(supervisionScopeService.assignedInternships(principalOf(supervisorB, "SUPERVISOR")))
                .extracting(Internship::getId)
                .contains(internshipB.getId())
                .doesNotContain(internshipA.getId());
        assertThat(supervisionScopeService.isAssignedTo(principalOf(supervisorA, "SUPERVISOR"), internshipA.getId())).isTrue();
        assertThat(supervisionScopeService.isAssignedTo(principalOf(supervisorA, "SUPERVISOR"), internshipB.getId())).isFalse();

        // Supervisor A can change his task (scope) and is the resolved notification recipient.
        mockMvc.perform(patch("/api/internships/tasks/" + taskA.getId() + "/status")
                        .param("status", "COMPLETED")
                        .header("Authorization", "Bearer " + token(supervisorA, "ROLE_SUPERVISOR")))
                .andExpect(status().isOk());

        assertThat(notificationMessages(supervisorA, "SUPERVISOR"))
                .anyMatch(message -> message.contains("Task UB A " + run));
        assertThat(notificationMessages(supervisorB, "SUPERVISOR"))
                .noneMatch(message -> message.contains("Task UB A " + run));

        // The other supervisor cannot even read the task (404, no existence leak).
        mockMvc.perform(get("/api/internships/tasks/" + taskA.getId())
                        .header("Authorization", "Bearer " + token(supervisorB, "ROLE_SUPERVISOR")))
                .andExpect(status().isNotFound());
        assertThat(taskB.getId()).isNotNull();
    }

    @Test
    @DisplayName("the Admin supervises through his own user-backed assignment while keeping global access (Scenario 1)")
    void adminActsAsSupervisorThroughHisOwnAssignment() {
        Internship mine = newInternship("INT-ADM-SELF-" + run);
        activeAssignment(mine, admin);
        Internship other = newInternship("INT-ADM-OTHER-" + run);
        activeAssignment(other, supervisorA);

        assertThat(supervisionScopeService.hasGlobalAccess(adminPrincipal)).isTrue();
        assertThat(supervisionScopeService.isAssignedTo(adminPrincipal, mine.getId())).isTrue();
        // Global access does NOT widen his own supervision scope: Scenario 1 relies
        // on the assignment link exactly like a Supervisor's scope does.
        assertThat(supervisionScopeService.assignedInternships(adminPrincipal))
                .extracting(Internship::getId)
                .contains(mine.getId())
                .doesNotContain(other.getId());
        assertThat(supervisionScopeService.findSupervisorUserId(mine.getId()))
                .contains(admin.getId());
    }

    @Test
    @DisplayName("reassignment moves the user-backed scope to the new supervisor and notifies both")
    void reassignmentMovesScopeAndNotifiesBoth() throws Exception {
        Internship internship = newInternship("INT-REASSIGN-" + run);
        internship.setSupervisorUser(supervisorA);
        internshipRepository.saveAndFlush(internship);
        activeAssignment(internship, supervisorA);

        supervisorManagementService.reassignSupervisor(internship.getId(),
                new ReassignSupervisorRequest(supervisorB.getId(), "Scope move " + run),
                adminPrincipal);

        // Scope move proven against the database, not a mock.
        assertThat(supervisionScopeService.assignedInternships(principalOf(supervisorA, "SUPERVISOR")))
                .extracting(Internship::getId)
                .doesNotContain(internship.getId());
        assertThat(supervisionScopeService.assignedInternships(principalOf(supervisorB, "SUPERVISOR")))
                .extracting(Internship::getId)
                .contains(internship.getId());
        assertThat(supervisionScopeService.findSupervisorUserId(internship.getId()))
                .contains(supervisorB.getId());

        // Exactly one ACTIVE assignment remains (partial unique index respected).
        assertThat(assignmentRepository.findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE))
                .isPresent()
                .get()
                .extracting(a -> a.getSupervisorUser().getId())
                .isEqualTo(supervisorB.getId());
        assertThat(assignmentRepository.findByInternshipId(internship.getId()))
                .filteredOn(a -> a.getStatus() == AssignmentStatus.REASSIGNED)
                .hasSize(1);

        // Both sides were notified (§5.6).
        assertThat(notificationMessages(supervisorA, "SUPERVISOR"))
                .anyMatch(message -> message.contains(internship.getReference()));
        assertThat(notificationMessages(supervisorB, "SUPERVISOR"))
                .anyMatch(message -> message.contains(internship.getReference()));
    }

    @Test
    @DisplayName("the selectable directory lists the Admin and every Supervisor exactly once (no duplicate identities)")
    void directoryListsAdminAndSupervisorsExactlyOnce() {
        // A user holding BOTH roles must not appear twice.
        User bothDraft = new User("both_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        bothDraft.getAssignedRoles().add(roleRepository.findByCode("ADMIN").orElseThrow());
        bothDraft.getAssignedRoles().add(roleRepository.findByCode("SUPERVISOR").orElseThrow());
        final User both = userRepository.saveAndFlush(bothDraft);

        List<SupervisorOption> options = supervisorDirectoryService.listSelectableSupervisors();

        assertThat(options).extracting(SupervisorOption::email)
                .contains(admin.getEmail(), supervisorA.getEmail(), supervisorB.getEmail(), both.getEmail());
        assertThat(options.stream().filter(o -> o.userId().equals(both.getId()))).hasSize(1);
        assertThat(options).extracting(SupervisorOption::userId).doesNotHaveDuplicates();

        // Disabled accounts are not selectable.
        User disabled = newUser("disabled", "SUPERVISOR");
        disabled.setEnabled(false);
        userRepository.saveAndFlush(disabled);
        assertThat(supervisorDirectoryService.listSelectableSupervisors())
                .extracting(SupervisorOption::email)
                .doesNotContain(disabled.getEmail());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UserPrincipal principalOf(User user, String roleCode) {
        return new UserPrincipal(user.getId(), user.getEmail(), List.of("ROLE_" + roleCode));
    }

    private List<String> notificationMessages(User recipient, String roleCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token(recipient, "ROLE_" + roleCode)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        List<String> messages = new java.util.ArrayList<>();
        content.forEach(node -> messages.add(node.get("message").asText()));
        return messages;
    }

    private User newUser(String prefix, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Internship newInternship(String reference) {
        Candidate candidate = newCandidate(reference);
        Internship internship = new Internship(reference, candidate,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setApplication(null);
        internship.setStatus(InternshipStatus.APPROVED);
        return internshipRepository.saveAndFlush(internship);
    }

    private void activeAssignment(Internship internship, User supervisorUser) {
        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        // Only the user-backed link: legacy supervisor_id / assigned_by_id stay NULL.
        assignment.setSupervisorUser(supervisorUser);
        assignment.setAssignedByUser(supervisorUser);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignmentPort().saveAndFlush(assignment);
    }

    /** The infrastructure repository extends both the JPA interface and the domain
     *  port, so {@code saveAndFlush} is ambiguous without an explicit view. */
    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }

    private Candidate newCandidate(String label) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest((label + run).getBytes(StandardCharsets.UTF_8)));
            Candidate candidate = new Candidate(
                    "Candidat", label, "cand_" + label.toLowerCase() + "_" + run + "@steg.tn",
                    cinHash, university);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
