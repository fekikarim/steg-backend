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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.companion.application.dto.ValidationRequest;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Task Scoping & Notification Routing Integration Tests")
class TaskScopingIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

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
    private TaskRepository taskRepository;

    @Autowired
    private InternshipService internshipService;

    private MockMvc mockMvc;

    private User adminUser;
    private String adminToken;

    private User supervisorAUser;
    private String supervisorAToken;

    private User supervisorBUser;
    private String supervisorBToken;

    private Department department;
    private University university;

    private Internship internshipA;
    private Internship internshipB;
    private Internship internshipAdmin;

    private Task taskA;
    private Task taskB;
    private Task taskAdmin;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElse(null);

        adminUser = userRepository.saveAndFlush(new User("admin_scope_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (adminRole != null) adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorAUser = userRepository.saveAndFlush(new User("sup_a_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (supervisorRole != null) supervisorAUser.getAssignedRoles().add(supervisorRole);
        supervisorAUser = userRepository.saveAndFlush(supervisorAUser);
        supervisorAToken = jwtService.generateAccessToken(supervisorAUser.getId(), supervisorAUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        supervisorBUser = userRepository.saveAndFlush(new User("sup_b_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (supervisorRole != null) supervisorBUser.getAssignedRoles().add(supervisorRole);
        supervisorBUser = userRepository.saveAndFlush(supervisorBUser);
        supervisorBToken = jwtService.generateAccessToken(supervisorBUser.getId(), supervisorBUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        department = departmentRepository.saveAndFlush(new Department("DEPT_TASK_" + suffix, "Dept Tasks", "IT"));
        university = universityRepository.saveAndFlush(new University("UNI_TASK_" + suffix, "Uni Tasks"));

        Employee empA = new Employee("EMP-A-" + suffix, "Moncef", "A", department);
        empA.setUser(supervisorAUser);
        employeeRepository.saveAndFlush(empA);

        Employee empB = new Employee("EMP-B-" + suffix, "Nabil", "B", department);
        empB.setUser(supervisorBUser);
        employeeRepository.saveAndFlush(empB);

        UserPrincipal adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        // Candidate A -> Internship A supervised by Supervisor A
        Candidate candA = createCandidate("A_" + suffix, "a_" + suffix + "@steg.tn");
        InternshipResponse respA = internshipService.createManual(new InternshipCreateManualRequest(
                candA.getId(), LocalDate.now(), LocalDate.now().plusMonths(2), "Project A", "Ingénieur", false, supervisorAUser.getId()
        ), adminPrincipal);
        internshipA = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository).findById(respA.id()).orElseThrow();

        // Candidate B -> Internship B supervised by Supervisor B
        Candidate candB = createCandidate("B_" + suffix, "b_" + suffix + "@steg.tn");
        InternshipResponse respB = internshipService.createManual(new InternshipCreateManualRequest(
                candB.getId(), LocalDate.now(), LocalDate.now().plusMonths(2), "Project B", "Ingénieur", false, supervisorBUser.getId()
        ), adminPrincipal);
        internshipB = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository).findById(respB.id()).orElseThrow();

        // Candidate Admin -> Internship Admin supervised by Admin himself
        Candidate candAdmin = createCandidate("Adm_" + suffix, "adm_" + suffix + "@steg.tn");
        InternshipResponse respAdmin = internshipService.createManual(new InternshipCreateManualRequest(
                candAdmin.getId(), LocalDate.now(), LocalDate.now().plusMonths(2), "Project Admin", "Ingénieur", false, adminUser.getId()
        ), adminPrincipal);
        internshipAdmin = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository).findById(respAdmin.id()).orElseThrow();

        // Create tasks
        taskA = taskRepository.saveAndFlush(new Task(internshipA, adminUser, "Task A", "Desc A"));
        taskB = taskRepository.saveAndFlush(new Task(internshipB, adminUser, "Task B", "Desc B"));
        taskAdmin = taskRepository.saveAndFlush(new Task(internshipAdmin, adminUser, "Task Admin", "Desc Admin"));
    }

    private Candidate createCandidate(String name, String email) throws Exception {
        User u = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(name.getBytes(StandardCharsets.UTF_8)));
        Candidate c = new Candidate("First_" + name, "Last_" + name, email, cinHash, university);
        c.setUser(u);
        return candidateRepository.saveAndFlush(c);
    }

    @Test
    @DisplayName("Admin sees all tasks globally; supervisors see only their assigned candidates' tasks")
    void globalVsScopedTaskListing() throws Exception {
        // Admin sees all 3 tasks globally
        mockMvc.perform(get("/api/internships/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        // Supervisor A sees only Task A
        MvcResult resA = mockMvc.perform(get("/api/internships/tasks")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentA = objectMapper.readTree(resA.getResponse().getContentAsString()).get("content");
        assertThat(contentA).allMatch(node -> node.get("title").asText().equals("Task A"));

        // Supervisor B sees only Task B
        MvcResult resB = mockMvc.perform(get("/api/internships/tasks")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentB = objectMapper.readTree(resB.getResponse().getContentAsString()).get("content");
        assertThat(contentB).allMatch(node -> node.get("title").asText().equals("Task B"));
    }

    @Test
    @DisplayName("Supervisor A cannot read, change, review, or create Supervisor B's tasks (returns 404, not 403)")
    void supervisorCannotAccessOtherSupervisorTasks() throws Exception {
        // 1. Read tasks of internship B -> 404
        mockMvc.perform(get("/api/internships/" + internshipB.getId() + "/tasks")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // 2. Read single task B -> 404
        mockMvc.perform(get("/api/internships/tasks/" + taskB.getId())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // 3. Create task on internship B -> 404
        TaskRequest newReq = new TaskRequest("Illegal Task", "Desc", null, null, null);
        mockMvc.perform(post("/api/internships/" + internshipB.getId() + "/tasks")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newReq)))
                .andExpect(status().isNotFound());

        // 4. Update task details on task B -> 404
        TaskRequest updateReq = new TaskRequest("Updated Title", "Desc", null, null, null);
        mockMvc.perform(put("/api/internships/tasks/" + taskB.getId())
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound());

        // 5. Update task status on task B -> 404
        mockMvc.perform(patch("/api/internships/tasks/" + taskB.getId() + "/status?status=IN_PROGRESS")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // 6. Review task B -> 404
        ValidationRequest reviewReq = new ValidationRequest(true, null);
        mockMvc.perform(post("/api/internships/tasks/" + taskB.getId() + "/review")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewReq)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Task status notification goes only to supervising supervisor; admin gets notifications only for his own candidates; reassignment shifts notifications")
    void taskNotificationRoutingAndReassignment() throws Exception {
        // 1. Task A status changed -> Supervisor A notified, Supervisor B NOT notified, Admin NOT notified
        mockMvc.perform(patch("/api/internships/tasks/" + taskA.getId() + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());

        // Check Supervisor A notifications
        MvcResult notifA = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentNotifA = objectMapper.readTree(notifA.getResponse().getContentAsString()).get("content");
        assertThat(contentNotifA).anyMatch(n -> n.get("title").asText().contains("Task status changed")
                && n.get("message").asText().contains("Task A"));

        // Check Supervisor B notifications -> should NOT have Task A notification
        MvcResult notifB = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentNotifB = objectMapper.readTree(notifB.getResponse().getContentAsString()).get("content");
        assertThat(contentNotifB).noneMatch(n -> n.get("message").asText().contains("Task A"));

        // Check Admin notifications -> Admin is NOT supervisor of Internship A, so no Task A notification
        MvcResult notifAdmin = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentNotifAdmin = objectMapper.readTree(notifAdmin.getResponse().getContentAsString()).get("content");
        assertThat(contentNotifAdmin).noneMatch(n -> n.get("message").asText().contains("Task A"));

        // 2. Admin self-supervised candidate: Task Admin status changed -> Admin IS notified
        mockMvc.perform(patch("/api/internships/tasks/" + taskAdmin.getId() + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        MvcResult notifAdminAfterSelf = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentNotifAdminSelf = objectMapper.readTree(notifAdminAfterSelf.getResponse().getContentAsString()).get("content");
        assertThat(contentNotifAdminSelf).anyMatch(n -> n.get("title").asText().contains("Task status changed")
                && n.get("message").asText().contains("Task Admin"));

        // 3. Reassign Internship A to Supervisor B
        UserPrincipal adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        internshipService.assign(internshipA.getId(), new InternshipAssignmentRequest(
                department.getId(), supervisorBUser.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(2), "Reassign to B"
        ), adminPrincipal);

        // Update Task A status again -> now Supervisor B receives it, and Supervisor A does NOT receive the new status
        mockMvc.perform(patch("/api/internships/tasks/" + taskA.getId() + "/status?status=IN_PROGRESS")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk());

        MvcResult notifBAfterReassign = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contentBAfter = objectMapper.readTree(notifBAfterReassign.getResponse().getContentAsString()).get("content");
        assertThat(contentBAfter).anyMatch(n -> n.get("title").asText().contains("Task status changed")
                && n.get("message").asText().contains("Task 'Task A' is now IN_PROGRESS"));
    }
}
