package tn.steg.backend.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.companion.application.dto.BulkTaskAction;
import tn.steg.backend.companion.application.dto.BulkTaskMutation;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.companion.application.dto.ValidationRequest;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12a Scenario A — Admin supervises an obligatory PFE, end to end over HTTP
 * against a real Postgres (AGENTS.md §12).
 *
 * <p>ONE chain: candidate registers + validates → Admin notified → application
 * submitted → Admin notified → approve with the Admin preselected as
 * supervisor → credentials in the response (no-store) + email attempted →
 * student logs in (forced password change) → Admin bulk-adds tasks to two
 * students (manual + AI drafts from a specs PDF) → student completes tasks →
 * Admin notified → approve/deny → report + journal submitted → AI
 * verification (fake python-ai) → manual decisions → VALIDATED → receipt →
 * certificate → audit rows for every step with correct sources → dashboard
 * numbers reflect the data.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S12a — Scenario A: Admin supervises obligatory PFE (Testcontainers HTTP chain)")
class ScenarioAAdminSupervisesChainTest {

    /** Scripted fake: the real Gemini API is never called. */
    @TestConfiguration
    static class FakeGeminiConfig {
        @Bean
        @Primary
        AiCompletionClient aiCompletionClient() {
            return Mockito.mock(AiCompletionClient.class);
        }
    }

    /** Recording fake: proves the credentials email is attempted with the same secret. */
    @TestConfiguration
    static class FakeMailConfig {
        @Bean
        @Primary
        EmailSender emailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {
        final List<String> recipients = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            recipients.add(to);
            bodies.add(body);
        }
    }

    /** Fake python-ai: report + journal both PASS check by check. */
    static HttpServer stubAi;

    static {
        try {
            stubAi = HttpServer.create(new InetSocketAddress(0), 0);
            String pass = """
                    {"overall":"PASS","checks":[
                    {"key":"candidate_name","label":"Candidate name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"supervisor_name","label":"Supervisor name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"internship_type","label":"Internship type","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"internship_period","label":"Internship period","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"steg_name","label":"STEG company name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"university_name","label":"University name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                    {"key":"page_count","label":"Page count","status":"PASSED","expected":">= 4","found":"6","evidence":"e"}]}""";
            stubAi.createContext("/api/verification/report", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = pass.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            stubAi.createContext("/api/verification/journal", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = pass.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
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
    static void pythonProps(DynamicPropertyRegistry registry) {
        registry.add("steg.python.base-url",
                () -> "http://localhost:" + stubAi.getAddress().getPort());
        registry.add("steg.python.service-token", () -> "s12a-scenario-a-token");
        registry.add("steg.python.max-attempts", () -> "1");
        registry.add("steg.python.circuit-breaker-threshold", () -> "1000");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiCompletionClient fakeGemini;
    @Autowired private EmailSender emailSender;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private University university;
    private Department department;
    private String run;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(fakeGemini);
        when(fakeGemini.getModel()).thenReturn("fake-model");
        when(fakeGemini.getProvider()).thenReturn("gemini");
        run = uid();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminUser = userRepository.saveAndFlush(new User("admin_a_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        university = universityRepository.saveAndFlush(new University("UNI_A_" + run, "Scenario A Uni " + run));
        department = departmentRepository.saveAndFlush(new Department("DIR_A_" + run, "Scenario A Dept", "SA"));

        // Finance/certificate flows require a linked employee profile for the decider.
        tn.steg.backend.organization.domain.model.Employee adminEmployee =
                new tn.steg.backend.organization.domain.model.Employee(
                        "EMP-A-" + run, "Admin", "ScenarioA", department);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);
    }

    private record Student(UUID applicationId, UUID internshipId, String email, String tempPassword,
                           UUID candidateUserId, String internToken) {
    }

    private record Registration(String token, UUID candidateId) {
    }

    @Test
    @DisplayName("Scenario A: register → approve (admin supervises) → tasks → validation → receipt → certificate")
    void adminSupervisesObligatoryPfeEndToEnd() throws Exception {
        RecordingEmailSender mail = (RecordingEmailSender) emailSender;
        int mailBefore = mail.recipients.size();

        // 1. Two candidates register on the front office and validate their accounts.
        String candEmail1 = "pfe_a1_" + run + "@steg.tn";
        Registration reg1 = registerAndCreateProfile(candEmail1, "CIN-A1-" + run);
        String candEmail2 = "pfe_a2_" + run + "@steg.tn";
        Registration reg2 = registerAndCreateProfile(candEmail2, "CIN-A2-" + run);
        String candToken1 = reg1.token();
        String candToken2 = reg2.token();

        // Admin is notified of each new validated candidate; supervisors are not.
        assertThat(deliveriesFor(adminUser.getId(), "Candidate", reg1.candidateId())).isNotEmpty();
        assertThat(deliveriesFor(adminUser.getId(), "Candidate", reg2.candidateId())).isNotEmpty();
        // Both candidates are visible in the Admin Candidates workspace.
        mockMvc.perform(get("/api/candidates/manage?q=" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));

        // 2. Both submit an obligatory PFE application (> 3 months ⇒ PFE OBLIGATOIRE).
        UUID app1 = createAndSubmitApplication(candToken1, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 6, 5));
        UUID app2 = createAndSubmitApplication(candToken2, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 6, 5));

        // Admin is notified of each new application.
        assertThat(deliveriesFor(adminUser.getId(), "InternshipApplication", app1)).isNotEmpty();
        assertThat(deliveriesFor(adminUser.getId(), "InternshipApplication", app2)).isNotEmpty();

        // 3. Admin approves with HIMSELF preselected as supervisor (null = approving Admin).
        Student s1 = approveAsAdminSupervisor(app1, candEmail1);
        Student s2 = approveAsAdminSupervisor(app2, candEmail2);

        for (Student s : List.of(s1, s2)) {
            assertThat(s.internshipId()).isNotNull();
        }

        // The students start work: APPROVED → IN_PROGRESS through the single authority.
        transitionToInProgress(s1.internshipId());
        transitionToInProgress(s2.internshipId());

        // Credentials were in the response AND emailed with the same secret.
        assertThat(mail.recipients.subList(mailBefore, mail.recipients.size()))
                .contains(s1.email(), s2.email());
        assertThat(String.join("\n", mail.bodies)).contains(s1.tempPassword());

        // 4. The student logs in and is forced to change the password.
        String studentToken = loginExpectMustChange(s1.email(), s1.tempPassword());
        mockMvc.perform(get("/api/candidates/me")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        changePassword(studentToken, s1.tempPassword(), "ChangedStrong!2026PassA");
        String freshLogin = loginExpectNoMustChange(s1.email(), "ChangedStrong!2026PassA");

        // 5a. Admin bulk-adds manual tasks to BOTH students in one atomic action.
        String bulkKey = "s12a-a-" + run;
        List<BulkTaskMutation> manual = List.of(
                new BulkTaskMutation(BulkTaskAction.CREATE, s1.internshipId(), null,
                        new TaskRequest("A manual task 1", "desc", null, null, null)),
                new BulkTaskMutation(BulkTaskAction.CREATE, s2.internshipId(), null,
                        new TaskRequest("A manual task 2", "desc", null, null, null)));
        MvcResult bulkResult = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", bulkKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manual)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("OK"))
                .andExpect(jsonPath("$.items[1].status").value("OK"))
                .andReturn();
        JsonNode bulkJson = objectMapper.readTree(bulkResult.getResponse().getContentAsString());
        String manualTask1 = bulkJson.path("tasks").path(0).path("id").asText();
        String manualTask2 = bulkJson.path("tasks").path(1).path("id").asText();
        assertThat(manualTask1).isNotBlank();
        assertThat(manualTask2).isNotBlank();

        // 5b. Admin generates AI drafts from a specs PDF, then bulk-adds them to both students.
        when(fakeGemini.complete(any(), anyList())).thenReturn(AiCompletionResult.success("""
                {"tasks": [
                  {"title": "Analyse the existing maintenance workflow", "description": "Interview the team", "dueDate": "2026-02-10"},
                  {"title": "Draft the intervention report outline", "description": "", "dueDate": "2026-02-20"}
                ]}""", "fake-model", "gemini"));
        MvcResult generated = mockMvc.perform(multipart("/api/internships/tasks/drafts/generate")
                        .file(new MockMultipartFile("file", "specs.pdf", "application/pdf",
                                pdfBytes("STEG maintenance specifications SPEC-" + run, "Plan the overhaul.")))
                        .param("internshipId", s1.internshipId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode drafts = objectMapper.readTree(generated.getResponse().getContentAsString());
        assertThat(drafts.size()).isEqualTo(2);
        List<UUID> draftIds = new ArrayList<>();
        for (JsonNode d : drafts) {
            draftIds.add(UUID.fromString(d.path("id").asText()));
        }
        MvcResult draftBulk = mockMvc.perform(post("/api/internships/tasks/drafts/bulk-add")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", "s12a-drafts-" + run)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("draftIds", draftIds.stream().map(UUID::toString).toList(),
                                        "internshipIds", List.of(s1.internshipId().toString(), s2.internshipId().toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andReturn();
        JsonNode draftBulkJson = objectMapper.readTree(draftBulk.getResponse().getContentAsString());
        assertThat(draftBulkJson.path("items").size()).isEqualTo(4);

        // Collect the intern's task ids (manual + AI) for student 1.
        MvcResult listed = mockMvc.perform(get("/api/internships/" + s1.internshipId() + "/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tasks = objectMapper.readTree(listed.getResponse().getContentAsString());
        List<String> internTaskIds = new ArrayList<>();
        for (JsonNode t : tasks.isArray() ? tasks : tasks.path("content")) {
            internTaskIds.add(t.path("id").asText());
        }
        assertThat(internTaskIds).hasSizeGreaterThanOrEqualTo(3);

        // 6. The student completes tasks in the mobile app → Admin (own supervisor) is notified.
        int adminTaskNotesBefore = countTaskDeliveries(adminUser.getId(), internTaskIds);
        for (String taskId : internTaskIds) {
            mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status")
                            .param("status", "COMPLETED")
                            .header("Authorization", "Bearer " + s1.internToken()))
                    .andExpect(status().isOk());
        }
        assertThat(countTaskDeliveries(adminUser.getId(), internTaskIds))
                .isGreaterThan(adminTaskNotesBefore);

        // Admin approves one and denies one with a reason.
        mockMvc.perform(post("/api/internships/tasks/" + internTaskIds.get(0) + "/review")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest(true, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/internships/tasks/" + internTaskIds.get(1) + "/review")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest(false, "Missing evidence"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        // 7. The student sends the report + journal → validation queue → AI checks → manual decisions.
        submitDeliverable(s1.internshipId(), "Rapport de stage", s1.internToken());
        submitDeliverable(s1.internshipId(), "Journal de stage", s1.internToken());

        mockMvc.perform(get("/api/internship-validation/queue?q=" + candEmail1.split("@")[0])
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(post("/api/internship-validation/" + s1.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("PASS"))
                .andExpect(jsonPath("$.checks.length()").value(7));
        mockMvc.perform(post("/api/internship-validation/" + s1.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"JOURNAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("PASS"));

        decide(s1.internshipId(), "REPORT", "VALIDATED", null);
        decide(s1.internshipId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(get("/api/internship-validation/" + s1.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        // 8. Certificate (while VALIDATED — the eligibility gate) + payment receipt (idempotent).
        // NOTE: the certificate gate is exactly VALIDATED (assumption #17), so the
        // certificate is generated before the receipt moves the internship to RECEIPT_ISSUED.
        MvcResult certificate = mockMvc.perform(post("/api/internships/" + s1.internshipId() + "/certificates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").exists())
                .andReturn();
        String certRef = objectMapper.readTree(certificate.getResponse().getContentAsString())
                .get("reference").asText();
        assertThat(certRef).startsWith("CERT-");

        MvcResult receipt = mockMvc.perform(post("/api/internship-validation/" + s1.internshipId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").exists())
                .andExpect(jsonPath("$.currency").value("TND"))
                .andReturn();
        String receiptRef = objectMapper.readTree(receipt.getResponse().getContentAsString())
                .get("reference").asText();
        assertThat(receiptRef).startsWith("PAY-");
        MvcResult receiptAgain = mockMvc.perform(post("/api/internship-validation/" + s1.internshipId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(receiptAgain.getResponse().getContentAsString())
                .get("reference").asText()).isEqualTo(receiptRef);

        // 9. Every step left an audit row, with the documented source.
        for (String action : List.of(
                "CANDIDATE_VALIDATED",
                "APPLICATION_CREATED", "APPLICATION_SUBMITTED",
                "APPLICATION_APPROVED_WITH_SUPERVISOR",
                "COMPANION_TASK_CREATED",
                "AI_TASK_DRAFTS_GENERATED", "AI_TASK_DRAFTS_BULK_ADDED",
                "COMPANION_TASK_STATUS_CHANGED",
                "TASK_APPROVED", "TASK_DENIED",
                "DELIVERABLE_CREATED", "DELIVERABLE_SUBMITTED",
                "AI_VERIFICATION_RUN",
                "VALIDATION_DECISION_VALIDATED",
                "PAYMENT_RECEIPT_ISSUED",
                "CERTIFICATE_GENERATED")) {
            assertThat(auditHas(action)).as("audit holds %s", action).isTrue();
        }
        assertThat(auditSourcesOf("CANDIDATE_VALIDATED")).contains(AuditSource.BACK_OFFICE);
        // Task writes keep the default channel (assumption #20: shared endpoints
        // cannot be labeled honestly, so BACK_OFFICE) — for intern AND admin alike.
        assertThat(auditSourcesOf("COMPANION_TASK_STATUS_CHANGED")).contains(AuditSource.BACK_OFFICE);
        assertThat(auditSourcesOf("DELIVERABLE_SUBMITTED")).contains(AuditSource.MOBILE);
        assertThat(auditSourcesOf("AI_VERIFICATION_RUN")).contains(AuditSource.AI);

        // 10. The Admin dashboard reflects the data.
        MvcResult summary = mockMvc.perform(get("/api/reports/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode admin = objectMapper.readTree(summary.getResponse().getContentAsString());
        assertThat(admin.path("applicationsByStatus").path("APPROVED").asLong())
                .isGreaterThanOrEqualTo(2);
        assertThat(admin.path("newCandidates").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(admin.path("receiptsIssued").asLong()).isGreaterThanOrEqualTo(1);
        // Assumption #33: issued = generated + non-revoked, so our generated
        // certificate is counted by the tile.
        assertThat(admin.path("certificatesIssued").asLong()).isGreaterThanOrEqualTo(1);
        boolean adminInWorkload = false;
        for (JsonNode row : admin.path("supervisorWorkload")) {
            if (row.path("supervisorUserId").asText().equals(adminUser.getId().toString())
                    && row.path("candidateCount").asLong() >= 2) {
                adminInWorkload = true;
            }
        }
        assertThat(adminInWorkload).as("admin supervises both students in the workload").isTrue();

        // The generated certificate is listed in the Admin certificates workspace.
        MvcResult certList = mockMvc.perform(get("/api/certificates?q=" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(certList.getResponse().getContentAsString()).contains(certRef);

        // No secret ever lands in the audit trail for this chain.
        for (String action : List.of("APPLICATION_APPROVED_WITH_SUPERVISOR", "AI_VERIFICATION_RUN",
                "VALIDATION_DECISION_VALIDATED", "PAYMENT_RECEIPT_ISSUED", "CERTIFICATE_GENERATED")) {
            for (AuditLog log : auditLogRepository
                    .findByAction(action, org.springframework.data.domain.Pageable.unpaged())) {
                String payload = String.valueOf(log.getNewValues()) + String.valueOf(log.getOldValues());
                assertThat(payload).doesNotContain(s1.tempPassword());
            }
        }
        assertThat(freshLogin).isNotBlank();
        assertThat(candToken2).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Helpers (all state transitions go through HTTP, never repositories)
    // ------------------------------------------------------------------

    private Registration registerAndCreateProfile(String email, String cin) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "SmokePass!2026",
                                "firstName", "S12a",
                                "lastName", "Student " + run))))
                .andExpect(status().isCreated())
                .andReturn();
        String candidateToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("accessToken").asText();
        assertThat(candidateToken).isNotBlank();

        MvcResult profile = mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "S12a",
                                "lastName", "Student " + run,
                                "email", email,
                                "universityId", university.getId().toString(),
                                "nationalId", cin,
                                "speciality", "Génie Électrique",
                                "diploma", "Licence"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper.readTree(profile.getResponse().getContentAsString())
                .get("id").asText());
        return new Registration(candidateToken, candidateId);
    }

    private UUID createAndSubmitApplication(String candidateToken, LocalDate start, LocalDate end) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("desiredStartDate", start.toString(),
                                        "desiredEndDate", end.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        UUID appId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
        mockMvc.perform(post("/api/applications/" + appId + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        return appId;
    }

    private Student approveAsAdminSupervisor(UUID applicationId, String candidateEmail) throws Exception {
        MvcResult approved = mockMvc.perform(post("/api/applications/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.supervisorUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.credentialEmail").isNotEmpty())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(approved.getResponse().getContentAsString());
        UUID internshipId = UUID.fromString(body.get("internship").get("id").asText());
        String tempPassword = body.get("temporaryPassword").asText();

        User provisioned = userRepository.findByEmail(candidateEmail).orElseThrow();
        assertThat(provisioned.getMustChangePassword()).isTrue();
        String internToken = jwtService.generateAccessToken(
                provisioned.getId(), provisioned.getEmail(), List.of("ROLE_INTERN"));
        return new Student(applicationId, internshipId, candidateEmail, tempPassword,
                provisioned.getId(), internToken);
    }

    private String loginExpectMustChange(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String loginExpectNoMustChange(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private void changePassword(String token, String current, String next) throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("currentPassword", current, "newPassword", next))))
                .andExpect(status().isNoContent());
    }

    private void transitionToInProgress(UUID internshipId) throws Exception {
        mockMvc.perform(post("/api/internships/" + internshipId + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"work starts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    private void submitDeliverable(UUID internshipId, String title, String internToken) throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(new MockMultipartFile("file", title + ".pdf", "application/pdf",
                                "%PDF-1.4 s12a deliverable".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("title", title)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID deliverableId = UUID.fromString(objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .get("id").asText());
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
    }

    private void decide(UUID internshipId, String documentType, String decision, String comment) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("documentType", documentType);
        body.put("decision", decision);
        if (comment != null) {
            body.put("comment", comment);
        }
        mockMvc.perform(post("/api/internship-validation/" + internshipId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private List<tn.steg.backend.notification.domain.model.NotificationDelivery> deliveriesFor(
            UUID recipientId, String entityType, UUID entityId) {
        return notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc(entityType, entityId).stream()
                .flatMap(n -> deliveryRepository.findByNotificationId(n.getId()).stream())
                .filter(d -> d.getRecipient() != null && recipientId.equals(d.getRecipient().getId()))
                .toList();
    }

    private int countTaskDeliveries(UUID recipientId, List<String> taskIds) {
        int total = 0;
        for (String taskId : taskIds) {
            total += deliveriesFor(recipientId, "Task", UUID.fromString(taskId)).size();
        }
        return total;
    }

    private boolean auditHas(String action) {
        return !auditLogRepository
                .findByAction(action, org.springframework.data.domain.Pageable.unpaged()).isEmpty();
    }

    private List<AuditSource> auditSourcesOf(String action) {
        return auditLogRepository.findByAction(action, org.springframework.data.domain.Pageable.unpaged())
                .stream().map(AuditLog::getSource).filter(s -> s != null).toList();
    }

    private static byte[] pdfBytes(String... lines) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.beginText();
                stream.newLineAtOffset(50, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -20);
                }
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
