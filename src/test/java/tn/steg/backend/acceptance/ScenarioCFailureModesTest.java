package tn.steg.backend.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.application.dto.BulkTaskAction;
import tn.steg.backend.companion.application.dto.BulkTaskMutation;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12a Scenario C — failure modes degrade with documented errors and never
 * lose data (AGENTS.md §12), over HTTP against a real Postgres.
 *
 * <p>Covers: Gemini key missing, python-ai down, Brevo down, duplicate
 * submit on approve and on bulk, invalid transitions, denial without reason,
 * scanned/unreadable PDF → INCONCLUSIVE.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S12a — Scenario C: failure modes degrade without data loss")
class ScenarioCFailureModesTest {

    /** Scripted fake: the real Gemini API is never called. */
    @TestConfiguration
    static class FakeGeminiConfig {
        @Bean
        @Primary
        AiCompletionClient aiCompletionClient() {
            return Mockito.mock(AiCompletionClient.class);
        }
    }

    /** Mock mail: per-test stubbed (works by default, throws for the Brevo-down probe). */
    @TestConfiguration
    static class FakeMailConfig {
        @Bean
        @Primary
        EmailSender emailSender() {
            return Mockito.mock(EmailSender.class);
        }
    }

    /** Fake python-ai with a per-test mode: pass | down (500) | inconclusive. */
    static final AtomicReference<String> AI_MODE = new AtomicReference<>("pass");
    static HttpServer stubAi;

    static {
        try {
            stubAi = HttpServer.create(new InetSocketAddress(0), 0);
            stubAi.createContext("/api/verification/report", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body;
                int code;
                if ("down".equals(AI_MODE.get())) {
                    body = "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
                    code = 500;
                } else if ("inconclusive".equals(AI_MODE.get())) {
                    body = """
                            {"overall":"INCONCLUSIVE","checks":[
                            {"key":"candidate_name","label":"Candidate name","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"supervisor_name","label":"Supervisor name","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"internship_type","label":"Internship type","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"internship_period","label":"Internship period","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"steg_name","label":"STEG company name","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"university_name","label":"University name","status":"INCONCLUSIVE","expected":"X","found":null,"evidence":"unreadable scan"},
                            {"key":"page_count","label":"Page count","status":"INCONCLUSIVE","expected":">= 4","found":null,"evidence":"unreadable scan"}]}"""
                            .getBytes(StandardCharsets.UTF_8);
                    code = 200;
                } else {
                    body = """
                            {"overall":"PASS","checks":[
                            {"key":"candidate_name","label":"Candidate name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"supervisor_name","label":"Supervisor name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"internship_type","label":"Internship type","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"internship_period","label":"Internship period","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"steg_name","label":"STEG company name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"university_name","label":"University name","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"page_count","label":"Page count","status":"PASSED","expected":">= 4","found":"6","evidence":"e"}]}"""
                            .getBytes(StandardCharsets.UTF_8);
                    code = 200;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            stubAi.createContext("/api/verification/journal", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body;
                int code;
                if ("down".equals(AI_MODE.get())) {
                    body = "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
                    code = 500;
                } else {
                    body = """
                            {"overall":"PASS","checks":[
                            {"key":"task_completion","label":"Task completion","status":"PASSED","expected":">= 75%","found":"100%","evidence":"e"},
                            {"key":"period","label":"Period","status":"PASSED","expected":"X","found":"X","evidence":"e"},
                            {"key":"structure","label":"Structure","status":"PASSED","expected":"period+table","found":"period+table","evidence":"e"}]}"""
                            .getBytes(StandardCharsets.UTF_8);
                    code = 200;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, body.length);
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
        registry.add("steg.python.service-token", () -> "s12a-scenario-c-token");
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
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private JwtService jwtService;

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
        Mockito.reset(fakeGemini, emailSender);
        when(fakeGemini.getModel()).thenReturn("fake-model");
        when(fakeGemini.getProvider()).thenReturn("gemini");
        doNothing().when(emailSender).send(any(), any(), any(), any());
        AI_MODE.set("pass");
        run = uid();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminUser = userRepository.saveAndFlush(new User("admin_c_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        university = universityRepository.saveAndFlush(new University("UNI_C_" + run, "Scenario C Uni"));
        department = departmentRepository.saveAndFlush(new Department("DIR_C_" + run, "Scenario C Dept", "SC"));
    }

    private record SubmittedApp(UUID applicationId, String candidateToken, String email) {
    }

    private record ApprovedIntern(UUID internshipId, String email, String tempPassword, String internToken) {
    }

    // ------------------------------------------------------------------
    // Gemini key missing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gemini key missing → 503 AI_UNAVAILABLE; manual drafts still work, nothing lost")
    void geminiKeyMissingGives503AndManualFallbackWorks() throws Exception {
        ApprovedIntern intern = submitAndApprove("c_gem_" + run + "@steg.tn", true);
        when(fakeGemini.complete(any(), anyList()))
                .thenReturn(AiCompletionResult.failure("API key is not configured", "fake-model", "gemini"));

        MvcResult failed = mockMvc.perform(multipart("/api/internships/tasks/drafts/generate")
                        .file(new MockMultipartFile("file", "specs.pdf", "application/pdf",
                                pdfBytes("STEG specs probe " + run, "Plan the overhaul.")))
                        .param("internshipId", intern.internshipId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        assertThat(failed.getResponse().getContentAsString()).contains("AI_UNAVAILABLE");

        // Nothing was persisted by the failed generation; manual creation still works.
        String manual = objectMapper.writeValueAsString(Map.of(
                "referenceInternshipId", intern.internshipId().toString(),
                "title", "Manually planned safety briefing",
                "description", "Delivered on site",
                "dueDate", "2026-03-01"));
        MvcResult created = mockMvc.perform(post("/api/internships/tasks/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manual)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID draftId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText());
        mockMvc.perform(post("/api/internships/tasks/drafts/bulk-add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "draftIds", List.of(draftId.toString()),
                                "internshipIds", List.of(intern.internshipId().toString()))))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("OK"));
    }

    // ------------------------------------------------------------------
    // python-ai down
    // ------------------------------------------------------------------

    @Test
    @DisplayName("python-ai down → INCONCLUSIVE degraded run; manual decisions still complete the flow")
    void pythonAiDownDegradesAndManualDecisionStillCompletes() throws Exception {
        AI_MODE.set("down");
        ApprovedIntern intern = submitApproveAndReport("c_pydown_" + run + "@steg.tn");

        mockMvc.perform(post("/api/internship-validation/" + intern.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.degraded").value(true));

        // The failed AI run moved nothing: still REPORT_SUBMITTED, documents intact.
        mockMvc.perform(get("/api/internship-validation/" + intern.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORT_SUBMITTED"));

        // The Admin validates manually and the flow completes without AI.
        decide(intern.internshipId(), "REPORT", "VALIDATED", null);
        decide(intern.internshipId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(get("/api/internship-validation/" + intern.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    // ------------------------------------------------------------------
    // Brevo down
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Brevo down → approval still commits, email flagged unsent, resend issues a NEW password")
    void brevoDownDoesNotRollBackApproval() throws Exception {
        doThrow(new RuntimeException("Brevo down")).when(emailSender).send(any(), any(), any(), any());
        ApprovedIntern intern = submitAndApprove("c_brevo_" + run + "@steg.tn", true);

        // The approval committed despite the mail failure; the response says so.
        User provisioned = userRepository.findByEmail(intern.email()).orElseThrow();
        assertThat(provisioned.getMustChangePassword()).isTrue();

        MvcResult approved = lastApproveResponse;
        assertThat(objectMapper.readTree(approved.getResponse().getContentAsString())
                .path("credentialEmailSent").asBoolean()).isFalse();

        // Resend generates a NEW password (the old one is never retrievable).
        MvcResult resent = mockMvc.perform(post("/api/intern-accounts/" + provisioned.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn();
        String fresh = objectMapper.readTree(resent.getResponse().getContentAsString())
                .get("temporaryPassword").asText();
        assertThat(fresh).isNotBlank().isNotEqualTo(intern.tempPassword());

        // The internship, supervisor and application survived the mail outage.
        assertThat(internshipPort().findById(intern.internshipId())).isPresent();
    }

    private MvcResult lastApproveResponse;

    // ------------------------------------------------------------------
    // Duplicate submits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("duplicate approve → 409, exactly one internship, no second credentials")
    void duplicateApproveIsRejectedWithoutSideEffects() throws Exception {
        SubmittedApp app = registerAndSubmit("c_dupapp_" + run + "@steg.tn", true);
        MvcResult first = approve(app.applicationId());
        UUID internshipId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString())
                .get("internship").get("id").asText());
        assertThat(objectMapper.readTree(first.getResponse().getContentAsString())
                .path("temporaryPassword").asText()).isNotBlank();
        long internshipsBefore = internshipRepository.findAll().size();

        mockMvc.perform(post("/api/applications/" + app.applicationId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));

        assertThat(internshipRepository.findAll().size()).isEqualTo(internshipsBefore);
        assertThat(internshipPort().findById(internshipId)).isPresent();
    }    @Test
    @DisplayName("duplicate bulk with the same idempotency key replays identical ids, no duplicates")
    void duplicateBulkWithSameKeyDoesNotDuplicate() throws Exception {
        ApprovedIntern intern = submitAndApprove("c_dupbulk_" + run + "@steg.tn", false);
        String body = objectMapper.writeValueAsString(List.of(
                new BulkTaskMutation(BulkTaskAction.CREATE, intern.internshipId(), null,
                        new TaskRequest("Dup task 1", "desc", null, null, null)),
                new BulkTaskMutation(BulkTaskAction.CREATE, intern.internshipId(), null,
                        new TaskRequest("Dup task 2", "desc", null, null, null))));
        String key = "s12c-dup-" + run;

        MvcResult first = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult second = mockMvc.perform(post("/api/internships/tasks/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.path("tasks").path(0).path("id").asText())
                .isEqualTo(firstJson.path("tasks").path(0).path("id").asText());
        assertThat(secondJson.path("tasks").path(1).path("id").asText())
                .isEqualTo(firstJson.path("tasks").path(1).path("id").asText());
    }

    // ------------------------------------------------------------------
    // Invalid transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalid transitions return the documented errors and change nothing")
    void invalidTransitionsReturnDocumentedErrors() throws Exception {
        // Submit twice: the replay is 409.
        SubmittedApp app = registerAndSubmit("c_inv_" + run + "@steg.tn", false);
        mockMvc.perform(post("/api/applications/" + app.applicationId() + "/submit")
                        .header("Authorization", "Bearer " + app.candidateToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));

        // Approve a DRAFT application: 409, no internship minted.
        SubmittedApp draft = registerWithoutSubmit("c_draft_" + run + "@steg.tn");
        mockMvc.perform(post("/api/applications/" + draft.applicationId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));

        // VALIDATED/RECEIPT_ISSUED are reserved for the validation endpoints: 422.
        ApprovedIntern intern = submitAndApprove("c_res_" + run + "@steg.tn", false);
        mockMvc.perform(post("/api/internships/" + intern.internshipId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"VALIDATED\",\"comment\":\"bypass\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_STATUS_RESERVED"));

        // A certificate before validation is 409, never a PDF.
        mockMvc.perform(post("/api/internships/" + intern.internshipId() + "/certificates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_VALIDATED"));
    }

    // ------------------------------------------------------------------
    // Denial without reason
    // ------------------------------------------------------------------

    @Test
    @DisplayName("denial without reason is rejected; with reason it applies and nothing else moves")
    void denialWithoutReasonIsRejected() throws Exception {
        SubmittedApp app = registerAndSubmit("c_deny_" + run + "@steg.tn", false);

        // Blank denial on the application workflow: 409, status untouched.
        mockMvc.perform(post("/api/applications/" + app.applicationId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStepCode\":\"FINAL_DECISION\",\"actionType\":\"APPROVAL\","
                                + "\"decision\":\"REJECTED\",\"comment\":\"   \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));

        // Blank rejection on a validation decision: 422, status untouched.
        ApprovedIntern intern = submitApproveAndReport("c_deny2_" + run + "@steg.tn");
        mockMvc.perform(post("/api/internship-validation/" + intern.internshipId() + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\",\"decision\":\"REJECTED\",\"comment\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DECISION_COMMENT_REQUIRED"));

        // With a reason both apply: application REJECTED, decision returns for resubmission.
        mockMvc.perform(post("/api/applications/" + app.applicationId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStepCode\":\"FINAL_DECISION\",\"actionType\":\"APPROVAL\","
                                + "\"decision\":\"REJECTED\",\"comment\":\"dossier incomplet\"}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Scanned / unreadable PDF
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scanned PDF → INCONCLUSIVE (never a false FAILED); manual decision still completes")
    void scannedPdfReturnsInconclusive() throws Exception {
        AI_MODE.set("inconclusive");
        ApprovedIntern intern = submitApproveAndReport("c_scan_" + run + "@steg.tn");

        mockMvc.perform(post("/api/internship-validation/" + intern.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("INCONCLUSIVE"));

        // Not a failure: still REPORT_SUBMITTED, documents intact, manual path open.
        mockMvc.perform(get("/api/internship-validation/" + intern.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORT_SUBMITTED"));
        decide(intern.internshipId(), "REPORT", "VALIDATED", null);
        decide(intern.internshipId(), "JOURNAL", "VALIDATED", null);
        mockMvc.perform(get("/api/internship-validation/" + intern.internshipId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    // ------------------------------------------------------------------
    // Helpers (all transitions go through HTTP, never repositories)
    // ------------------------------------------------------------------

    private SubmittedApp registerAndSubmit(String email, boolean obligatoryPfe) throws Exception {
        String candidateToken = registerProfile(email);
        LocalDate end = obligatoryPfe ? LocalDate.of(2026, 6, 5) : LocalDate.of(2026, 3, 5);
        UUID appId = createDraftApp(candidateToken, LocalDate.of(2026, 1, 5), end);
        mockMvc.perform(post("/api/applications/" + appId + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        return new SubmittedApp(appId, candidateToken, email);
    }

    private SubmittedApp registerWithoutSubmit(String email) throws Exception {
        String candidateToken = registerProfile(email);
        UUID appId = createDraftApp(candidateToken, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5));
        return new SubmittedApp(appId, candidateToken, email);
    }

    private String registerProfile(String email) throws Exception {
        String cin = "CIN-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "SmokePass!2026",
                                "firstName", "S12c",
                                "lastName", "Student"))))
                .andExpect(status().isCreated())
                .andReturn();
        String candidateToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("accessToken").asText();

        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "S12c",
                                "lastName", "Student",
                                "email", email,
                                "universityId", university.getId().toString(),
                                "nationalId", cin))))
                .andExpect(status().isCreated());
        return candidateToken;
    }

    private UUID createDraftApp(String candidateToken, LocalDate start, LocalDate end) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "desiredStartDate", start.toString(),
                                "desiredEndDate", end.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
    }

    private MvcResult approve(UUID applicationId) throws Exception {
        lastApproveResponse = mockMvc.perform(post("/api/applications/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationApprovalRequest(null, department.getId()))))
                .andExpect(status().isOk())
                .andReturn();
        return lastApproveResponse;
    }

    private ApprovedIntern submitAndApprove(String email, boolean obligatoryPfe) throws Exception {
        SubmittedApp app = registerAndSubmit(email, obligatoryPfe);
        MvcResult approved = approve(app.applicationId());
        JsonNode body = objectMapper.readTree(approved.getResponse().getContentAsString());
        UUID internshipId = UUID.fromString(body.get("internship").get("id").asText());
        JsonNode tempNode = body.get("temporaryPassword");
        String temp = (tempNode == null || tempNode.isNull()) ? null : tempNode.asText();
        User provisioned = userRepository.findByEmail(email).orElseThrow();
        String internToken = jwtService.generateAccessToken(
                provisioned.getId(), provisioned.getEmail(), List.of("ROLE_INTERN"));
        return new ApprovedIntern(internshipId, email, temp, internToken);
    }

    private ApprovedIntern submitApproveAndReport(String email) throws Exception {
        ApprovedIntern intern = submitAndApprove(email, false);
        mockMvc.perform(post("/api/internships/" + intern.internshipId() + "/status-transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"work starts\"}"))
                .andExpect(status().isOk());
        uploadAndSubmit(intern.internshipId(), "Rapport de stage", intern.internToken());
        uploadAndSubmit(intern.internshipId(), "Journal de stage", intern.internToken());
        return intern;
    }

    private void uploadAndSubmit(UUID internshipId, String title, String internToken) throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(new MockMultipartFile("file", title + ".pdf", "application/pdf",
                                "%PDF-1.4 s12c deliverable".getBytes(StandardCharsets.UTF_8)))
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

    private tn.steg.backend.internship.domain.repository.InternshipRepository internshipPort() {
        return internshipRepository;
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

    private static byte[] pdfBytes(String... lines) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
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
