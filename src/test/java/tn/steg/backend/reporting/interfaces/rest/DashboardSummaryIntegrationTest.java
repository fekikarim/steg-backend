package tn.steg.backend.reporting.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.infrastructure.persistence.CertificateRepository;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.infrastructure.persistence.FileAssetRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.finance.infrastructure.persistence.FinanceCaseRepository;
import tn.steg.backend.finance.infrastructure.persistence.PaymentReceiptRepository;
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
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S11 dashboard summaries (AGENTS.md §8.3, Testcontainers): dedicated scoped
 * backend summary endpoints. The same dataset yields different numbers for
 * the Admin (global) and Supervisor A (own scope); Supervisor B's data never
 * appears in A's numbers; counts per status are exact; an Admin who supervises
 * appears in the workload; soft-deleted candidates are excluded.
 *
 * <p>@Transactional: every test rolls back, so global Admin numbers are
 * asserted as before/after deltas while supervisor-scoped numbers (fresh
 * supervisor users own no other rows) are asserted exactly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("S11 — dashboard summaries (Testcontainers)")
class DashboardSummaryIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private FinanceCaseRepository financeCaseRepository;
    @Autowired private PaymentReceiptRepository paymentReceiptRepository;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private FileAssetRepository fileAssetRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String run;

    private User adminUser;
    private User supervisorAUser;
    private User supervisorBUser;
    private User supervisorEmptyUser;
    private String adminToken;
    private String supervisorAToken;
    private String supervisorBToken;
    private String supervisorEmptyToken;
    private String candidateToken;

    private Candidate candAdmin;
    private Candidate candA1;
    private Candidate candA2;
    private Candidate candB1;
    private Department department;
    private University university;
    private Employee adminEmployee;
    private UUID taskA1Id;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = uid();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();
        Role candidateRole = roleRepository.findByCode("CANDIDATE").orElseThrow();

        adminUser = saveUser("dash_admin_" + run, adminRole);
        supervisorAUser = saveUser("dash_supA_" + run, supervisorRole);
        supervisorBUser = saveUser("dash_supB_" + run, supervisorRole);
        supervisorEmptyUser = saveUser("dash_supE_" + run, supervisorRole);
        User candidateUser = saveUser("dash_cand_" + run, candidateRole);

        adminToken = token(adminUser, "ROLE_ADMIN");
        supervisorAToken = token(supervisorAUser, "ROLE_SUPERVISOR");
        supervisorBToken = token(supervisorBUser, "ROLE_SUPERVISOR");
        supervisorEmptyToken = token(supervisorEmptyUser, "ROLE_SUPERVISOR");
        candidateToken = token(candidateUser, "ROLE_CANDIDATE");

        department = departmentRepository.saveAndFlush(new Department("DASH-DIR-" + run, "Dashboard Dept", "DD"));
        Employee empA = saveEmployee("DASH-SUPA-" + run, supervisorAUser);
        Employee empB = saveEmployee("DASH-SUPB-" + run, supervisorBUser);
        adminEmployee = saveEmployee("DASH-ADM-" + run, adminUser);

        university = universityRepository.saveAndFlush(new University("DASH-UNI-" + run, "Dashboard Uni"));

        // Admin supervises candAdmin himself (Scenario 1, §3.1).
        candAdmin = saveCandidate("AdminOwn" + run, university, null);
        Internship internshipAdmin = saveInternship("INT-DASH-ADM-" + run, candAdmin, InternshipStatus.IN_PROGRESS);
        saveAssignment(internshipAdmin, adminEmployee);
        saveApplication("APP-DASH-ADM-" + run, candAdmin, ApplicationStatus.APPROVED);

        // Supervisor A: two candidates, mixed applications, tasks incl. overdue.
        candA1 = saveCandidate("AlphaOne" + run, university, null);
        saveApplication("APP-DASH-A1-" + run, candA1, ApplicationStatus.SUBMITTED);
        Internship internshipA1 = saveInternship("INT-DASH-A1-" + run, candA1, InternshipStatus.IN_PROGRESS);
        saveAssignment(internshipA1, empA);
        taskA1Id = saveTask(internshipA1, "Awaiting review task", TaskStatus.COMPLETED, LocalDate.now().plusDays(7)).getId();
        saveTask(internshipA1, "Overdue chore", TaskStatus.TODO, LocalDate.now().minusDays(3));
        saveTask(internshipA1, "Future chore", TaskStatus.TODO, LocalDate.now().plusDays(30));

        candA2 = saveCandidate("AlphaTwo" + run, university, null);
        saveApplication("APP-DASH-A2-" + run, candA2, ApplicationStatus.MODIFICATION_REQUESTED);
        Internship internshipA2 = saveInternship("INT-DASH-A2-" + run, candA2, InternshipStatus.REPORT_SUBMITTED);
        saveAssignment(internshipA2, empA);

        // Supervisor B: isolated world that must never leak into A's numbers.
        candB1 = saveCandidate("BetaOne" + run, university, null);
        saveApplication("APP-DASH-B1-" + run, candB1, ApplicationStatus.REJECTED);
        Internship internshipB1 = saveInternship("INT-DASH-B1-" + run, candB1, InternshipStatus.VALIDATED);
        saveAssignment(internshipB1, empB);
        saveTask(internshipB1, "Foreign completed task", TaskStatus.COMPLETED, LocalDate.now().plusDays(7));

        // Receipt + issued certificate on B's validated internship (global counts).
        FinanceCase financeCase = financeCaseRepository.saveAndFlush(new FinanceCase("FC-DASH-" + run, internshipB1));
        FileAsset pdf = fileAssetRepository.saveAndFlush(new FileAsset(
                "dash/" + run + ".pdf", "dash.pdf", "sha256-dash-" + run, "application/pdf", 128L, adminUser));
        PaymentReceipt receipt = new PaymentReceipt("RC-DASH-" + run, financeCase, pdf,
                new BigDecimal("150.00"), 3, "TND");
        paymentReceiptRepository.saveAndFlush(receipt);
        Certificate certificate = new Certificate("CERT-DASH-" + run, internshipB1, empB, pdf, "TPL", 1,
                LocalDate.now());
        certificateRepository.saveAndFlush(certificate);

        // Two notifications for Supervisor A (both unread).
        notificationService.dispatch("First notice", "hello A1", NotificationPriority.NORMAL,
                "Task", taskA1Id, List.of(supervisorAUser.getId()), adminUser.getId());
        notificationService.dispatch("Second notice", "hello A2", NotificationPriority.NORMAL,
                "Task", taskA1Id, List.of(supervisorAUser.getId()), adminUser.getId());
    }

    private User saveUser(String localPart, Role role) {
        User user = userRepository.saveAndFlush(new User(localPart + "@steg.tn", "hash", UserStatus.ACTIVE));
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Employee saveEmployee(String code, User user) {
        Employee emp = new Employee(code, "Dash", "Emp", department);
        emp.setUser(user);
        return employeeRepository.saveAndFlush(emp);
    }

    private Candidate saveCandidate(String tag, University uni, User owner) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag,
                ("dash_" + tag + "_" + run + "@steg.tn").toLowerCase(), hash, uni);
        if (owner != null) {
            candidate.setUser(owner);
        }
        return candidateRepository.saveAndFlush(candidate);
    }

    private void saveApplication(String reference, Candidate candidate, ApplicationStatus status) {
        applicationRepository.saveAndFlush(new InternshipApplication(reference, candidate, status));
    }

    private Internship saveInternship(String reference, Candidate candidate, InternshipStatus status) {
        Internship internship = new Internship(reference, candidate,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(status);
        internship.setSupervisorUser(null);
        return internshipRepository.saveAndFlush(internship);
    }

    private void saveAssignment(Internship internship, Employee supervisor) {
        InternshipAssignment assignment = new InternshipAssignment(internship, department, supervisor, supervisor,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                AssignmentStatus.ACTIVE);
        assignment.setSupervisorUser(supervisor.getUser());
        ((org.springframework.data.jpa.repository.JpaRepository<InternshipAssignment, UUID>) assignmentRepository)
                .saveAndFlush(assignment);
        internship.setSupervisorUser(supervisor.getUser());
        internshipRepository.saveAndFlush(internship);
    }

    private Task saveTask(Internship internship, String title, TaskStatus status, LocalDate dueDate) {
        Task task = new Task(internship, adminUser, title, "dashboard seed");
        task.setStatus(status);
        task.setDueDate(dueDate);
        return taskRepository.saveTask(task);
    }

    private JsonNode adminSummary() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reports/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode supervisorSummary(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reports/supervisor-summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // Scope: same dataset, different numbers per role
    // ------------------------------------------------------------------

    @Test
    @DisplayName("same dataset gives global numbers to Admin and own-scope numbers to Supervisor A")
    void adminGlobalVersusSupervisorOwnScope() throws Exception {
        JsonNode admin = adminSummary();
        JsonNode supA = supervisorSummary(supervisorAToken);

        // Supervisor A: exactly his world — 2 candidates, A1 IN_PROGRESS + A2 REPORT_SUBMITTED.
        assertThat(supA.path("myCandidates").asLong()).isEqualTo(2);
        assertThat(supA.path("candidatesByStatus").path("IN_PROGRESS").asLong()).isEqualTo(1);
        assertThat(supA.path("candidatesByStatus").path("REPORT_SUBMITTED").asLong()).isEqualTo(1);
        assertThat(supA.path("candidatesByStatus").path("VALIDATED").asLong()).isZero();
        // Awaiting approval: only A1's COMPLETED task (B's COMPLETED task is invisible).
        assertThat(supA.path("tasksAwaitingApproval").asLong()).isEqualTo(1);
        // Overdue: only the TODO chore past due (future + completed excluded).
        assertThat(supA.path("overdueTasks").asLong()).isEqualTo(1);
        // Notifications: the two seeded deliveries, one still unread.
        assertThat(supA.path("unreadNotifications").asLong()).isEqualTo(2);
        assertThat(supA.path("recentNotifications").size()).isEqualTo(2);

        // Admin sees at least A's world inside strictly larger-or-equal globals.
        assertThat(admin.path("tasksAwaitingApproval").asLong())
                .isGreaterThanOrEqualTo(supA.path("tasksAwaitingApproval").asLong() + 1);
        assertThat(admin.path("reportsAwaitingValidation").path("REPORT_SUBMITTED").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(admin.path("receiptsIssued").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(admin.path("certificatesIssued").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Supervisor B data never appears in Supervisor A numbers")
    void supervisorBIsInvisibleToSupervisorA() throws Exception {
        JsonNode supA = supervisorSummary(supervisorAToken);
        String payload = supA.toString();
        assertThat(payload).doesNotContain("BetaOne");
        assertThat(payload).doesNotContain("INT-DASH-B1-" + run);
        assertThat(payload).doesNotContain("Foreign completed task");

        JsonNode supB = supervisorSummary(supervisorBToken);
        assertThat(supB.path("myCandidates").asLong()).isEqualTo(1);
        assertThat(supB.path("candidatesByStatus").path("VALIDATED").asLong()).isEqualTo(1);
        assertThat(supB.path("tasksAwaitingApproval").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("counts per status are exact, zeros included")
    void countsPerStatusAreExact() throws Exception {
        JsonNode supA = supervisorSummary(supervisorAToken);
        JsonNode statuses = supA.path("candidatesByStatus");
        assertThat(statuses.path("APPROVED").asLong()).isZero();
        assertThat(statuses.path("IN_PROGRESS").asLong()).isEqualTo(1);
        assertThat(statuses.path("REPORT_SUBMITTED").asLong()).isEqualTo(1);
        assertThat(statuses.path("UNDER_VALIDATION").asLong()).isZero();
        assertThat(statuses.path("VALIDATED").asLong()).isZero();
        assertThat(statuses.path("RECEIPT_ISSUED").asLong()).isZero();
        assertThat(statuses.path("CANCELLED").asLong()).isZero();
        assertThat(statuses.path("ARCHIVED").asLong()).isZero();
    }

    @Test
    @DisplayName("an Admin who supervises candidates appears in the workload")
    void adminSupervisorAppearsInWorkload() throws Exception {
        JsonNode admin = adminSummary();
        JsonNode workload = admin.path("supervisorWorkload");
        boolean adminRow = false;
        boolean supARow = false;
        for (JsonNode row : workload) {
            if (row.path("supervisorUserId").asText().equals(adminUser.getId().toString())) {
                adminRow = true;
                assertThat(row.path("candidateCount").asLong()).isGreaterThanOrEqualTo(1);
            }
            if (row.path("supervisorUserId").asText().equals(supervisorAUser.getId().toString())) {
                supARow = true;
                assertThat(row.path("candidateCount").asLong()).isGreaterThanOrEqualTo(2);
            }
        }
        assertThat(adminRow).as("admin supervising row present").isTrue();
        assertThat(supARow).as("supervisor A row present").isTrue();
    }

    @Test
    @DisplayName("soft-deleted candidates are excluded from every number")
    void softDeletedCandidatesAreExcluded() throws Exception {
        Candidate extra = saveCandidate("Ghost" + run, university, null);
        JsonNode before = adminSummary();
        long candidatesBefore = before.path("newCandidates").asLong();

        mockMvc.perform(delete("/api/candidates/" + extra.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        JsonNode after = adminSummary();
        assertThat(after.path("newCandidates").asLong()).isEqualTo(candidatesBefore - 1);
    }

    @Test
    @DisplayName("a supervisor with no candidates sees zeros, never other data")
    void emptySupervisorSeesZeros() throws Exception {
        JsonNode sup = supervisorSummary(supervisorEmptyToken);
        assertThat(sup.path("myCandidates").asLong()).isZero();
        assertThat(sup.path("tasksAwaitingApproval").asLong()).isZero();
        assertThat(sup.path("overdueTasks").asLong()).isZero();
        assertThat(sup.path("candidatesByStatus").path("IN_PROGRESS").asLong()).isZero();
        assertThat(sup.path("unreadNotifications").asLong()).isZero();
        assertThat(sup.path("recentNotifications").size()).isZero();
    }

    @Test
    @DisplayName("trends cover six months in order with consistent totals")
    void trendsAreOrderedAndConsistent() throws Exception {
        JsonNode trends = adminSummary().path("trends");
        assertThat(trends.size()).isEqualTo(6);
        String previous = "";
        long applications = 0;
        long internships = 0;
        for (JsonNode point : trends) {
            String month = point.path("month").asText();
            assertThat(month).matches("\\d{4}-\\d{2}");
            assertThat(month.compareTo(previous) > 0).isTrue();
            previous = month;
            assertThat(point.path("applications").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(point.path("internships").asLong()).isGreaterThanOrEqualTo(0);
            applications += point.path("applications").asLong();
            internships += point.path("internships").asLong();
        }
        JsonNode admin = adminSummary();
        assertThat(applications).isEqualTo(sumValues(admin.path("applicationsByStatus")));
    }

    private long sumValues(JsonNode map) {
        long sum = 0;
        var fields = map.fields();
        while (fields.hasNext()) {
            sum += fields.next().getValue().asLong();
        }
        return sum;
    }

    @Test
    @DisplayName("generated certificate counts as issued; revoked one drops out of the tile")
    void generatedCountsAsIssuedAndRevokedDropsOut() throws Exception {
        // Fresh VALIDATED internship with no certificate yet (assumption #33:
        // issued = generated + non-revoked). The candidate needs a linked
        // account: generation notifies the intern user.
        User internUser = saveUser("dash_issued_" + run, roleRepository.findByCode("INTERN").orElseThrow());
        Candidate fresh = saveCandidate("IssuedTile" + run, university, internUser);
        Internship internship = saveInternship("INT-DASH-ISSUED-" + run, fresh, InternshipStatus.VALIDATED);
        saveAssignment(internship, adminEmployee);

        long before = adminSummary().path("certificatesIssued").asLong();

        MvcResult generated = mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andReturn();
        UUID certificateId = UUID.fromString(objectMapper.readTree(
                generated.getResponse().getContentAsString()).get("id").asText());
        assertThat(adminSummary().path("certificatesIssued").asLong()).isEqualTo(before + 1);

        mockMvc.perform(delete("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        assertThat(adminSummary().path("certificatesIssued").asLong()).isEqualTo(before);
    }

    @Test
    @DisplayName("candidates and anonymous callers cannot reach the summaries")
    void roleGatesHold() throws Exception {
        mockMvc.perform(get("/api/reports/admin-summary")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/supervisor-summary")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/admin-summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/supervisor-summary")).andExpect(status().isUnauthorized());
        // Supervisor is refused on the Admin-only summary.
        mockMvc.perform(get("/api/reports/admin-summary")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isForbidden());
    }
}
