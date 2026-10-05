package tn.steg.backend.companion.interfaces.rest;

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
import tn.steg.backend.companion.application.dto.BulkTaskAction;
import tn.steg.backend.companion.application.dto.BulkTaskMutation;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6c — atomic bulk task mutations (AGENTS.md §5.5/§6.1), proven against real
 * Postgres: all-or-nothing rollback, idempotent double submit, and supervisor
 * scope (out-of-scope student → 404, nothing written).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S6c — atomic bulk task mutations (Testcontainers)")
class TaskBulkAtomicityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private TaskRepository taskRepository;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorAUser;
    private String supervisorAToken;
    private User supervisorBUser;
    private Department department;
    private University university;
    private Internship internshipA;
    private Internship internshipB;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElse(null);

        adminUser = userRepository.saveAndFlush(new User("admin_bulk_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (adminRole != null) adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorAUser = userRepository.saveAndFlush(new User("supA_bulk_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (supervisorRole != null) supervisorAUser.getAssignedRoles().add(supervisorRole);
        supervisorAUser = userRepository.saveAndFlush(supervisorAUser);
        supervisorAToken = jwtService.generateAccessToken(supervisorAUser.getId(), supervisorAUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        supervisorBUser = userRepository.saveAndFlush(new User("supB_bulk_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (supervisorRole != null) supervisorBUser.getAssignedRoles().add(supervisorRole);
        supervisorBUser = userRepository.saveAndFlush(supervisorBUser);

        department = departmentRepository.saveAndFlush(new Department("DEPT_BLK_" + suffix, "Dept Bulk", "IT"));
        university = universityRepository.saveAndFlush(new University("UNI_BLK_" + suffix, "Uni Bulk"));

        Employee empA = new Employee("EMP-BLK-A-" + suffix, "Bulk", "A", department);
        empA.setUser(supervisorAUser);
        employeeRepository.saveAndFlush(empA);

        internshipA = createInternship("Abulk_" + suffix, "ab_" + suffix + "@steg.tn", supervisorAUser.getId());
        internshipB = createInternship("Bbulk_" + suffix, "bb_" + suffix + "@steg.tn", supervisorBUser.getId());
    }

    private Internship createInternship(String name, String email, UUID supervisorUserId) {
        try {
            User u = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(digest.digest(name.getBytes(StandardCharsets.UTF_8)));
            Candidate c = new Candidate("First_" + name, "Last_" + name, email, cinHash, university);
            c.setUser(u);
            c = candidateRepository.saveAndFlush(c);
            InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                    c.getId(), LocalDate.now(), LocalDate.now().plusMonths(2),
                    "Bulk project", "Ingénieur", false, supervisorUserId), adminPrincipal);
            return ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                    .findById(resp.id()).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<BulkTaskMutation> createsFor(UUID internshipId, String prefix, int n) {
        java.util.ArrayList<BulkTaskMutation> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BulkTaskMutation(BulkTaskAction.CREATE, internshipId, null,
                    new TaskRequest(prefix + " task " + i, "desc", null, null, null)));
        }
        return out;
    }

    private int taskCount(UUID internshipId) {
        return taskRepository.findByInternshipId(internshipId).size();
    }

    @Test
    @DisplayName("a failing item rolls back the whole bulk (all-or-nothing)")
    void failingItemRollsBackEverything() throws Exception {
        var mutations = new java.util.ArrayList<>(createsFor(internshipA.getId(), "rollback", 2));
        // Third item is invalid: blank title → BULK_TASK_INVALID.
        mutations.add(new BulkTaskMutation(BulkTaskAction.CREATE, internshipA.getId(), null,
                new TaskRequest("  ", "no title", null, null, null)));

        mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mutations)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BULK_TASK_INVALID"));

        assertThat(taskCount(internshipA.getId()))
                .as("no task of the failed bulk may survive").isZero();
        assertThat(taskCount(internshipB.getId())).isZero();
    }

    @Test
    @DisplayName("resubmitting the same bulk with the same idempotency key does not duplicate")
    void resubmittingSameKeyDoesNotDuplicate() throws Exception {
        var mutations = createsFor(internshipA.getId(), "idem", 2);
        String body = objectMapper.writeValueAsString(mutations);
        String key = "bulk-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("OK"))
                .andExpect(jsonPath("$.items[1].status").value("OK"))
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String firstId0 = firstJson.path("tasks").path(0).path("id").asText();
        String firstId1 = firstJson.path("tasks").path(1).path("id").asText();
        assertThat(firstId0).isNotBlank();
        assertThat(taskCount(internshipA.getId())).isEqualTo(2);

        MvcResult second = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.path("tasks").path(0).path("id").asText()).isEqualTo(firstId0);
        assertThat(secondJson.path("tasks").path(1).path("id").asText()).isEqualTo(firstId1);
        assertThat(taskCount(internshipA.getId()))
                .as("replay must not create duplicates").isEqualTo(2);
    }

    @Test
    @DisplayName("bulk UPDATE across two students succeeds with a per-item summary")
    void bulkUpdateAcrossStudentsSucceedsWithPerItemSummary() throws Exception {
        // Seed one task per student through the bulk endpoint itself.
        var seeds = new java.util.ArrayList<>(createsFor(internshipA.getId(), "seed-a", 1));
        seeds.addAll(createsFor(internshipB.getId(), "seed-b", 1));
        MvcResult seeded = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seeds)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode seededJson = objectMapper.readTree(seeded.getResponse().getContentAsString());
        String idA = seededJson.path("tasks").path(0).path("id").asText();
        String idB = seededJson.path("tasks").path(1).path("id").asText();

        // One atomic UPDATE across both students.
        var updates = List.of(
                new BulkTaskMutation(BulkTaskAction.UPDATE, null, UUID.fromString(idA),
                        new TaskRequest("Renamed A", null, null, null, null)),
                new BulkTaskMutation(BulkTaskAction.UPDATE, null, UUID.fromString(idB),
                        new TaskRequest("Renamed B", null, null, null, null)));
        mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.items[0].taskId").value(idA))
                .andExpect(jsonPath("$.items[0].status").value("OK"))
                .andExpect(jsonPath("$.items[1].taskId").value(idB))
                .andExpect(jsonPath("$.items[1].status").value("OK"));

        assertThat(port().findById(UUID.fromString(idA)).orElseThrow().getTitle())
                .isEqualTo("Renamed A");
        assertThat(port().findById(UUID.fromString(idB)).orElseThrow().getTitle())
                .isEqualTo("Renamed B");
    }

    private tn.steg.backend.companion.domain.repository.TaskRepository port() {
        return taskRepository;
    }

    @Test
    @DisplayName("a supervisor bulk containing an out-of-scope student fails with 404 and writes nothing")
    void supervisorBulkWithOutOfScopeStudentFails404() throws Exception {
        var mutations = new java.util.ArrayList<>(createsFor(internshipA.getId(), "scope", 1));
        // internshipB belongs to supervisor B — supervisor A must get 404.
        mutations.addAll(createsFor(internshipB.getId(), "foreign", 1));

        mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mutations)))
                .andExpect(status().isNotFound());

        assertThat(taskCount(internshipA.getId()))
                .as("atomicity: the in-scope item must roll back too").isZero();
        assertThat(taskCount(internshipB.getId())).isZero();

        // Sanity: the global task list never leaks across supervisors.
        MvcResult listed = mockMvc.perform(get("/api/internships/tasks")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listed.getResponse().getContentAsString()).doesNotContain("foreign task");
    }
}
