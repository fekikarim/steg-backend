package tn.steg.backend.internship.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S7 internship validation end to end (AGENTS.md §5.11, Testcontainers): the
 * deliverables channel moves IN_PROGRESS → REPORT_SUBMITTED with supervisor +
 * Admin notifications; the Admin-only queue/detail/verify/decide/receipt
 * surface works with a stubbed python-ai (report PASS, journal DOWN);
 * REJECTED returns for resubmission; the receipt is idempotent; supervisors
 * get 403.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S7 — internship validation API (Testcontainers)")
class InternshipValidationApiTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 s7 validation file content".getBytes(StandardCharsets.UTF_8);

    static HttpServer stubAi;
    static final List<String> journalBodies = new CopyOnWriteArrayList<>();

    static {
        try {
            stubAi = HttpServer.create(new InetSocketAddress(0), 0);
            stubAi.createContext("/api/verification/report", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = """
                        {"overall":"PASS","checks":[
                        {"key":"candidate_name","label":"Candidate name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"supervisor_name","label":"Supervisor name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"internship_type","label":"Internship type","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"internship_period","label":"Internship period","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"steg_name","label":"STEG company name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"university_name","label":"University name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                        {"key":"page_count","label":"Page count","status":"PASSED","expected":">= 4","found":"6","evidence":"e"}]}"""
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            stubAi.createContext("/api/verification/journal", exchange -> {
                journalBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            stubAi.setExecutor(Executors.newCachedThreadPool());
            stubAi.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void pythonProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("steg.python.base-url",
                () -> "http://localhost:" + stubAi.getAddress().getPort());
        registry.add("steg.python.service-token", () -> "s7-test-token");
        registry.add("steg.python.max-attempts", () -> "1");
        registry.add("steg.python.circuit-breaker-threshold", () -> "1000");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private tn.steg.backend.internship.application.InternshipLifecycleService lifecycleService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorAUser;
    private String supervisorAToken;
    private User supervisorBUser;
    private Department dept;
    private University uniA;
    private University uniB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        journalBodies.clear();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_s7_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        dept = departmentRepository.saveAndFlush(new Department("DIR_S7_" + uid(), "S7 Dept", "S7"));
        Employee adminEmployee = new Employee("EMP-S7-ADM-" + uid(), "Admin", "S7", dept);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        supervisorAUser = userRepository.saveAndFlush(new User("supA_s7_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorAUser.getAssignedRoles().add(supervisorRole);
        supervisorAUser = userRepository.saveAndFlush(supervisorAUser);
        supervisorAToken = jwtService.generateAccessToken(
                supervisorAUser.getId(), supervisorAUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        supervisorBUser = userRepository.saveAndFlush(new User("supB_s7_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorBUser.getAssignedRoles().add(supervisorRole);
        supervisorBUser = userRepository.saveAndFlush(supervisorBUser);

        Employee empA = new Employee("EMP-S7-A-" + uid(), "Sup", "A", dept);
        empA.setUser(supervisorAUser);
        employeeRepository.saveAndFlush(empA);

        uniA = universityRepository.saveAndFlush(new University("UNI_S7A_" + uid(), "S7 Uni A"));
        uniB = universityRepository.saveAndFlush(new University("UNI_S7B_" + uid(), "S7 Uni B"));
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Detached-safe fixture: ids/emails are captured inside the repository call. */
    private record FixtureCandidate(Candidate candidate, UUID userId, String email) {
    }

    private FixtureCandidate createCandidate(String tag, University uni) throws Exception {
        String email = "cand_" + tag + "_" + uid() + "@steg.tn";
        User u = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate c = new Candidate("First" + tag, "Last" + tag, email, cinHash, uni);
        c.setUser(u);
        UUID userId = u.getId();
        return new FixtureCandidate(candidateRepository.saveAndFlush(c), userId, email);
    }

    private String internTokenFor(FixtureCandidate fc) {
        return jwtService.generateAccessToken(fc.userId(), fc.email(), List.of("ROLE_INTERN"));
    }

    private Internship createInternship(Candidate candidate, UUID supervisorUserId,
                                        LocalDate start, LocalDate end) {
        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), start, end, "S7 project", "Ingénieur", false, supervisorUserId),
                adminPrincipal);
        return port().findById(resp.id()).orElseThrow();
    }

    private tn.steg.backend.internship.domain.repository.InternshipRepository port() {
        return internshipRepository;
    }

    private Internship atStatus(Internship internship, InternshipStatus status) {
        internship.setStatus(status);
        return internshipRepository.saveAndFlush(internship);
    }

    /** Drives APPROVED → IN_PROGRESS through the single authority (the intern works from there). */
    private Internship startProgress(Internship internship) {
        lifecycleService.transition(internship.getId(), InternshipStatus.IN_PROGRESS,
                "S7 test: work started", adminPrincipal);
        return port().findById(internship.getId()).orElseThrow();
    }

    private UUID uploadDeliverable(UUID internshipId, String title, String token) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(new MockMultipartFile("file", title + ".pdf", "application/pdf", PDF_BYTES))
                        .param("title", title)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void submitDeliverable(UUID deliverableId, String token) throws Exception {
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private boolean notified(UUID recipientId, String entityType, UUID entityId) {
        return notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc(entityType, entityId)
                .stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .anyMatch(d -> d.getRecipient() != null && recipientId.equals(d.getRecipient().getId()));
    }

    // ------------------------------------------------------------------
    // S7a.1 submission
    // ------------------------------------------------------------------

    @Test
    @DisplayName("intern submit moves IN_PROGRESS → REPORT_SUBMITTED and notifies supervisor + Admin")
    void internSubmitMovesToReportSubmittedAndNotifies() throws Exception {
        FixtureCandidate sub = createCandidate("sub", uniA);
        Candidate candidate = sub.candidate();
        String internToken = internTokenFor(sub);
        Internship internship = createInternship(candidate, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
        internship = startProgress(internship);

        UUID deliverableId = uploadDeliverable(internship.getId(), "Rapport de stage", internToken);
        submitDeliverable(deliverableId, internToken);

        Internship reloaded = port().findById(internship.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InternshipStatus.REPORT_SUBMITTED);
        assertThat(notified(supervisorAUser.getId(), "Internship", internship.getId())).isTrue();
        assertThat(notified(adminUser.getId(), "Internship", internship.getId())).isTrue();

        // A second submission changes nothing and fails nothing.
        UUID second = uploadDeliverable(internship.getId(), "Journal de stage", internToken);
        submitDeliverable(second, internToken);
        Internship again = port().findById(internship.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(InternshipStatus.REPORT_SUBMITTED);
    }

    // ------------------------------------------------------------------
    // Queue + scope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("queue lists the four stages with search, filters and paging; supervisor gets 403")
    void queueListsStagesWithFilters() throws Exception {
        // No @Transactional in this class (notifications need commits), so every
        // assertion isolates its fixtures through unique candidate emails.
        String tag = uid();
        FixtureCandidate f1 = createCandidate("q1" + tag, uniA);
        FixtureCandidate f2 = createCandidate("q2" + tag, uniB);
        FixtureCandidate f3 = createCandidate("q3" + tag, uniA);
        FixtureCandidate f4 = createCandidate("q4" + tag, uniA);
        Candidate c1 = f1.candidate();
        Candidate c2 = f2.candidate();
        Candidate c3 = f3.candidate();
        Candidate c4 = f4.candidate();
        Internship i1 = atStatus(createInternship(c1, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)), InternshipStatus.REPORT_SUBMITTED);
        Internship i2 = atStatus(createInternship(c2, supervisorBUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)), InternshipStatus.UNDER_VALIDATION);
        Internship i3 = atStatus(createInternship(c3, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)), InternshipStatus.VALIDATED);
        assertThat(i3.getStatus()).isEqualTo(InternshipStatus.VALIDATED);
        Internship i4 = atStatus(createInternship(c4, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1)), InternshipStatus.RECEIPT_ISSUED);
        String allQ = tag;

        // Search isolates exactly the four fixtures; paging applies.
        mockMvc.perform(get("/api/internship-validation/queue?q=" + allQ + "&size=2")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // Status filter.
        mockMvc.perform(get("/api/internship-validation/queue?q=" + allQ + "&statuses=REPORT_SUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].internshipId").value(i1.getId().toString()));

        // Supervisor filter.
        mockMvc.perform(get("/api/internship-validation/queue?q=" + allQ
                                + "&supervisorUserId=" + supervisorBUser.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].internshipId").value(i2.getId().toString()));

        // University + type + single-candidate search.
        mockMvc.perform(get("/api/internship-validation/queue?q=" + allQ + "&universityId=" + uniB.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/api/internship-validation/queue?q=" + allQ + "&type=PFE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].internshipId").value(i4.getId().toString()));
        mockMvc.perform(get("/api/internship-validation/queue?q=" + c1.getEmail().split("@")[0])
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].internshipId").value(i1.getId().toString()));

        // Supervisor is forbidden (Admin-only surface).
        mockMvc.perform(get("/api/internship-validation/queue")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internship-validation/" + i1.getId())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Detail + AI + decisions + receipt (full flow)
    // ------------------------------------------------------------------

    private Internship flowInternship() throws Exception {
        FixtureCandidate flow = createCandidate("flow" + uid(), uniA);
        Candidate candidate = flow.candidate();
        Internship internship = startProgress(createInternship(candidate, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)));
        // Shared ratio: 1 APPROVED of 2 (CANCELLED excluded).
        Task done = new Task(internship, adminUser, "Done task", "d");
        done.setStatus(TaskStatus.APPROVED);
        taskRepository.saveAndFlush(done);
        Task todo = new Task(internship, adminUser, "Open task", "d");
        todo.setStatus(TaskStatus.TODO);
        taskRepository.saveAndFlush(todo);
        Task cancelled = new Task(internship, adminUser, "Dropped task", "d");
        cancelled.setStatus(TaskStatus.CANCELLED);
        taskRepository.saveAndFlush(cancelled);
        // Two submitted deliverables: report + journal (assumption #18).
        String internToken = internTokenFor(flow);
        submitDeliverable(uploadDeliverable(internship.getId(), "Rapport de stage", internToken), internToken);
        submitDeliverable(uploadDeliverable(internship.getId(), "Journal de stage", internToken), internToken);
        return port().findById(internship.getId()).orElseThrow();
    }

    @Test
    @DisplayName("detail aggregates candidate, tasks+ratio, both documents, runs, decisions and receipt")
    void detailAggregatesEverything() throws Exception {
        Internship internship = flowInternship();

        mockMvc.perform(get("/api/internship-validation/" + internship.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.fullName", org.hamcrest.Matchers.startsWith("Firstflow")))
                .andExpect(jsonPath("$.tasks.total").value(2))
                .andExpect(jsonPath("$.tasks.done").value(1))
                .andExpect(jsonPath("$.tasks.items.length()").value(3))
                .andExpect(jsonPath("$.report.title").value("Rapport de stage"))
                .andExpect(jsonPath("$.journal.title").value("Journal de stage"))
                .andExpect(jsonPath("$.receipt").doesNotExist());
    }

    @Test
    @DisplayName("report AI PASS persists the run check by check; journal DOWN degrades without blocking")
    void aiPassAndDegradedPaths() throws Exception {
        Internship internship = flowInternship();

        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("PASS"))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.checks.length()").value(7))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"));

        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"JOURNAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.degraded").value(true));

        // The shared ratio inputs really reached python-ai.
        assertThat(journalBodies).isNotEmpty();
        assertThat(journalBodies.get(journalBodies.size() - 1)).contains("\"completedApprovedTasks\":1");
        assertThat(journalBodies.get(journalBodies.size() - 1)).contains("\"totalTasks\":2");

        // The detail exposes the latest runs.
        mockMvc.perform(get("/api/internship-validation/" + internship.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestReportRun.overall").value("PASS"))
                .andExpect(jsonPath("$.latestReportRun.checks.length()").value(7))
                .andExpect(jsonPath("$.latestJournalRun.degraded").value(true));
    }

    @Test
    @DisplayName("first decision opens validation; REJECTED without comment is refused")
    void firstDecisionOpensValidation() throws Exception {
        Internship internship = flowInternship();

        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\",\"decision\":\"REJECTED\",\"comment\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DECISION_COMMENT_REQUIRED"));

        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\",\"decision\":\"VALIDATED\",\"comment\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_VALIDATION"))
                .andExpect(jsonPath("$.reportDecision.decision").value("VALIDATED"));
    }

    @Test
    @DisplayName("REJECTED returns the internship for resubmission; both VALIDATED completes it")
    void rejectedReturnsAndBothValidatedCompletes() throws Exception {
        Internship internship = flowInternship();
        decide(internship.getId(), "REPORT", "VALIDATED", null);

        // REJECTED with comment → back to REPORT_SUBMITTED (assumption #18).
        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"JOURNAL\",\"decision\":\"REJECTED\",\"comment\":\"Add week 4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORT_SUBMITTED"))
                .andExpect(jsonPath("$.journalDecision.decision").value("REJECTED"));

        // Resubmission re-opens, then both VALIDATED completes.
        decide(internship.getId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(get("/api/internship-validation/" + internship.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    @DisplayName("rejection notifies the candidate with the comment; resubmission returns to REPORT_SUBMITTED")
    void rejectionNotifiesCandidateWithComment() throws Exception {
        FixtureCandidate fc = createCandidate("rej" + uid(), uniA);
        Internship internship = startProgress(createInternship(fc.candidate(), supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)));
        String internToken = internTokenFor(fc);
        submitDeliverable(uploadDeliverable(internship.getId(), "Rapport de stage", internToken), internToken);
        submitDeliverable(uploadDeliverable(internship.getId(), "Journal de stage", internToken), internToken);
        decide(internship.getId(), "REPORT", "VALIDATED", null);

        mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"JOURNAL\",\"decision\":\"REJECTED\",\"comment\":\"Add week 4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORT_SUBMITTED"));

        // The candidate — and only the candidate — got the comment.
        List<String> candidateMessages = notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc("Internship", internship.getId())
                .stream()
                .filter(n -> deliveryRepository.findByNotificationId(n.getId()).stream()
                        .anyMatch(d -> d.getRecipient() != null && fc.userId().equals(d.getRecipient().getId())))
                .map(n -> n.getMessage())
                .toList();
        assertThat(candidateMessages).anySatisfy(m -> assertThat(m).contains("Add week 4"));

        // Resubmitting a corrected journal keeps REPORT_SUBMITTED and the flow can complete.
        submitDeliverable(uploadDeliverable(internship.getId(), "Journal de stage v2", internToken), internToken);
        decide(internship.getId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(get("/api/internship-validation/" + internship.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    private void decide(UUID internshipId, String documentType, String decision, String comment) throws Exception {
        String body = comment == null
                ? "{\"documentType\":\"" + documentType + "\",\"decision\":\"" + decision + "\"}"
                : "{\"documentType\":\"" + documentType + "\",\"decision\":\"" + decision
                        + "\",\"comment\":\"" + comment + "\"}";
        mockMvc.perform(post("/api/internship-validation/" + internshipId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("receipt is idempotent with a unique number and moves to RECEIPT_ISSUED")
    void receiptIsIdempotent() throws Exception {
        Internship internship = flowInternship();
        decide(internship.getId(), "REPORT", "VALIDATED", null);
        decide(internship.getId(), "JOURNAL", "VALIDATED", null);

        MvcResult first = mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").exists())
                .andExpect(jsonPath("$.amount").exists())
                .andExpect(jsonPath("$.currency").value("TND"))
                .andReturn();
        String reference = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("reference").asText();
        assertThat(reference).startsWith("PAY-");
        String amount = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("amount").asText();
        String financeCaseId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("financeCaseId").asText();

        MvcResult second = mockMvc.perform(post("/api/internship-validation/" + internship.getId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(second.getResponse().getContentAsString())
                .get("reference").asText()).isEqualTo(reference);

        // Pre-check (b): the PDF text carries the unique number, the candidate
        // name, the internship type, the amount and the currency — and a
        // second download returns the same stored file.
        MvcResult downloadOne = mockMvc.perform(get("/api/finance-cases/" + financeCaseId + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String pdfTextOne = pdfText(downloadOne.getResponse().getContentAsByteArray());
        assertThat(pdfTextOne).contains(reference);
        assertThat(pdfTextOne).contains("Firstflow");
        assertThat(pdfTextOne).contains("perfectionnement");
        assertThat(pdfTextOne).contains(amount);
        assertThat(pdfTextOne).contains("TND");
        MvcResult downloadTwo = mockMvc.perform(get("/api/finance-cases/" + financeCaseId + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(pdfText(downloadTwo.getResponse().getContentAsByteArray())).isEqualTo(pdfTextOne);

        mockMvc.perform(get("/api/internship-validation/" + internship.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIPT_ISSUED"))
                .andExpect(jsonPath("$.receipt.reference").value(reference));
    }

    @Test
    @DisplayName("receipt refused before VALIDATED, for ineligible internships, and for supervisors")
    void receiptGuards() throws Exception {
        FixtureCandidate nog = createCandidate("nog", uniA);
        Candidate candidate = nog.candidate();
        Internship under = createInternship(candidate, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
        atStatus(under, InternshipStatus.UNDER_VALIDATION);
        mockMvc.perform(post("/api/internship-validation/" + under.getId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_VALIDATED"));

        // OPTIONAL internship validated through decisions → not payable.
        FixtureCandidate opt = createCandidate("opt", uniA);
        Candidate optional = opt.candidate();
        Internship optionalInternship = createInternship(optional, supervisorAUser.getId(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        atStatus(optionalInternship, InternshipStatus.VALIDATED);
        mockMvc.perform(post("/api/internship-validation/" + optionalInternship.getId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_PAYABLE"));

        // Supervisor is forbidden on the receipt endpoint.
        Internship validated = flowInternship();
        decide(validated.getId(), "REPORT", "VALIDATED", null);
        decide(validated.getId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(post("/api/internship-validation/" + validated.getId() + "/receipt")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("generic status-transitions still refuses VALIDATED and RECEIPT_ISSUED")
    void genericEndpointStillRejectsReserved() throws Exception {
        FixtureCandidate res = createCandidate("res", uniA);
        Candidate candidate = res.candidate();
        Internship internship = createInternship(candidate, supervisorAUser.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
        atStatus(internship, InternshipStatus.UNDER_VALIDATION);
        mockMvc.perform(post("/api/internships/" + internship.getId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"VALIDATED\",\"comment\":\"bypass\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_STATUS_RESERVED"));
    }
}
