package tn.steg.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12b security review — endpoint authorization, hand-reviewed against
 * AGENTS.md §3.3 (NOT derived from the annotations: the tables below are the
 * specification, the annotations the implementation, and the tripwire test
 * fails if they ever drift apart).
 *
 * <p>Three tables partition every Spring MVC endpoint (206 mappings):
 * <ul>
 *   <li>{@link #ADMIN_ONLY} (70) — §3.3 reserves the capability to Admin
 *       (applications/certificate/finance/validation/supervisor/account
 *       management, audit, org reference data, admin dashboards/reports).
 *       Probed: Supervisor → 403, anonymous → 401.</li>
 *   <li>{@link #SUPERVISOR_ALLOWED} (127) — §3.3 grants the capability to
 *       Supervisor (own-scope candidates/tasks/drafts/chatbot/notifications/
 *       conversations, shared candidate/application surfaces, scoped reads).
 *       Row-level 404 scoping inside these endpoints is proven per module
 *       (see the audit); this table only classifies the method-security
 *       layer.</li>
 *   <li>{@link #PUBLIC} (9) — anonymous entry points (§3.4 layer 1 public
 *       surface: auth, universities, public intake/validation).</li>
 * </ul>
 * <p>{@link #everyControllerEndpointIsClassified} enumerates the live
 * {@code RequestMappingHandlerMapping} and fails on any endpoint missing
 * from all three tables (STOMP/actuator are not Spring MVC mappings and are
 * covered by their own tests + config).
 *
 * <p>Method security runs before any business logic, so random UUIDs are
 * used — a 403/401 proves the gate itself, never the fixture. Mutating
 * endpoints carry a validation-passing body because {@code @Valid} argument
 * resolution runs before the {@code @PreAuthorize} proxy.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("S12b — endpoint authorization: hand-reviewed tables + tripwire")
class AdminOnlyEndpointSecurityTest {

    private static final String U1 = "11111111-1111-4111-8111-111111111111";
    private static final String U2 = "22222222-2222-4222-8222-222222222222";

    /** METHOD + Spring pattern ({var} placeholders), reviewed against §3.3. */
    static final List<String> ADMIN_ONLY = List.of(
            // §3.3 "Audit page" — Admin only.
            "GET /api/audit",
            "GET /api/audit/{id}",
            // §3.3 "Internship certificates" — Admin only.
            "POST /api/internships/{id}/certificates",
            "GET /api/certificates",
            "GET /api/certificates/{id}/detail",
            "PUT /api/certificates/{id}",
            "DELETE /api/certificates/{id}",
            // §3.3 "Internship applications management" — Admin only.
            "PUT /api/applications/{id}/documents/{documentId}/verify",
            "POST /api/applications/{id}/approve",
            "GET /api/applications/{id}/workflow",
            "POST /api/applications/{id}/workflow/actions",
            "GET /api/workflows/{instanceId}/actions",
            // §3.3 Admin-managed reference data (evaluation templates).
            "POST /api/evaluation-templates",
            "PUT /api/evaluation-templates/{templateId}",
            "DELETE /api/evaluation-templates/{templateId}",
            "POST /api/evaluation-templates/{templateId}/criteria",
            "PUT /api/evaluation-templates/criteria/{criterionId}",
            // §3.3 "Internship validation + payment receipt" — Admin only.
            "POST /api/finance-cases",
            "POST /api/finance-cases/{id}/documents",
            "PATCH /api/finance-cases/{id}/documents/{documentId}",
            "POST /api/finance-cases/{id}/recalculate",
            "GET /api/internship-validation/queue",
            "GET /api/internship-validation/{internshipId}",
            "POST /api/internship-validation/{internshipId}/verify",
            "POST /api/internship-validation/{internshipId}/decisions",
            "POST /api/internship-validation/{internshipId}/receipt",
            "GET /api/internship-validation/{internshipId}/documents/{documentType}/download",
            "POST /api/internships/{internshipId}/logbook/{logbookId}/official",
            // §3.3 "STEG intern (mobile) accounts management" — Admin only.
            "GET /api/intern-accounts",
            "GET /api/intern-accounts/manage",
            "GET /api/intern-accounts/{id}",
            "POST /api/intern-accounts",
            "PUT /api/intern-accounts/{id}",
            "DELETE /api/intern-accounts/{id}",
            "POST /api/intern-accounts/{id}/reset-password",
            "PATCH /api/intern-accounts/{id}/status?enabled=false",
            // §3.3 internship pipeline management — Admin only (creation,
            // lifecycle moves, assignment, document attach).
            "POST /api/internships/from-application",
            "POST /api/internships/manual",
            "POST /api/internships/{id}/status-transitions",
            "PUT /api/internships/{id}/dates",
            "POST /api/internships/{id}/cancel",
            "POST /api/internships/{id}/assignments",
            "POST /api/internships/{id}/documents",
            // §3.3 "Supervisor management + (re)assigning supervisor" — Admin only.
            "GET /api/supervisors",
            "GET /api/supervisors/manage",
            "GET /api/supervisors/{id}",
            "POST /api/supervisors",
            "PUT /api/supervisors/{id}",
            "DELETE /api/supervisors/{id}",
            "POST /api/supervisors/{id}/assign-candidate",
            "PUT /api/supervisors/{id}/reassign/{internshipId}",
            // §3.3 Admin-managed reference data (organization).
            "GET /api/departments",
            "GET /api/departments/{id}",
            "POST /api/departments",
            "PUT /api/departments/{id}",
            "DELETE /api/departments/{id}",
            "GET /api/employees",
            "GET /api/employees/{id}",
            "POST /api/employees",
            "PUT /api/employees/{id}",
            "DELETE /api/employees/{id}",
            // §3.3 "Dashboard: global stats" — Admin only (supervisor-summary is shared).
            "GET /api/reports/applications-by-status",
            "GET /api/reports/internships-by-type",
            "GET /api/reports/internships-by-status",
            "GET /api/reports/internships-by-department",
            "GET /api/reports/finance-cases-by-status",
            "GET /api/reports/payment-totals",
            "GET /api/reports/admin-summary",
            // §7.4/§7.5 advisory AI analysis — Admin only per code.
            "POST /api/ai/applications/{id}/analyze",
            "POST /api/ai/finance-cases/{id}/analyze");

    /** METHOD + Spring pattern, reviewed against §3.3 (Supervisor admitted at method level). */
    static final List<String> SUPERVISOR_ALLOWED = List.of(
            // §7.4/§7.5 + logbook: Supervisor admitted (own scope / participant).
            "POST /api/ai/assistant/query",
            "POST /api/ai/recommendations/{id}/review",
            "POST /api/ai/internships/{id}/logbook/generate",
            "POST /api/ai/chatbot/query",
            "GET /api/ai/chatbot/history",
            "DELETE /api/ai/chatbot/history",
            // §3.3 shared candidate/application surfaces (candidate-owned writes,
            // scoped reads; row-level outcomes proven per module).
            "POST /api/applications",
            "GET /api/applications",
            "GET /api/applications/manage",
            "GET /api/applications/{id}",
            "PUT /api/applications/{id}",
            "POST /api/applications/{id}/submit",
            "POST /api/applications/{id}/withdraw",
            "POST /api/applications/{id}/resubmit",
            "POST /api/candidates",
            "GET /api/candidates/me",
            "PUT /api/candidates/me",
            "GET /api/candidates",
            "GET /api/candidates/manage",
            "POST /api/candidates/manage",
            "GET /api/candidates/{id}",
            "GET /api/candidates/{id}/overview",
            "DELETE /api/candidates/{id}",
            "PUT /api/candidates/{id}",
            // §3.3 scoped certificate downloads (management stays admin-only).
            "GET /api/certificates/{id}",
            "GET /api/certificates/{id}/versions/{version}/download",
            // §8.1 participant comments.
            "POST /api/journal/entries/{entryId}/comments",
            "GET /api/journal/entries/{entryId}/comments",
            "POST /api/deliverables/{deliverableId}/comments",
            "GET /api/deliverables/{deliverableId}/comments",
            "POST /api/evaluations/{evaluationId}/comments",
            "GET /api/evaluations/{evaluationId}/comments",
            // §3.3 "Student tasks management: only own candidates' tasks".
            "POST /api/internships/{id}/tasks",
            "GET /api/internships/{id}/tasks",
            "GET /api/internships/tasks",
            "GET /api/internships/tasks/{taskId}",
            "PUT /api/internships/tasks/{taskId}",
            "PATCH /api/internships/tasks/{taskId}/status",
            "POST /api/internships/tasks/{taskId}/review",
            "POST /api/internships/tasks/bulk",
            "DELETE /api/internships/tasks/{taskId}",
            // §4 report/journal channel (participant-scoped).
            "GET /api/internships/{id}/journal/entries",
            "POST /api/internships/{id}/journal/entries",
            "POST /api/internships/journal/entries/{entryId}/submit",
            "POST /api/internships/journal/entries/{entryId}/validate",
            "POST /api/internships/journal/entries/{entryId}/reject",
            "GET /api/internships/{id}/deliverables",
            "GET /api/internships/deliverables/{deliverableId}",
            "POST /api/internships/{id}/deliverables",
            "POST /api/internships/deliverables/{deliverableId}/versions",
            "GET /api/internships/deliverables/{deliverableId}/versions",
            "POST /api/internships/deliverables/{deliverableId}/submit",
            "POST /api/internships/deliverables/{deliverableId}/validate",
            "POST /api/internships/deliverables/{deliverableId}/reject",
            "GET /api/internships/deliverables/{deliverableId}/download",
            // §3.3 logbook participant/supervisor surface (official is admin-only).
            "POST /api/internships/{internshipId}/logbook/submit",
            "POST /api/internships/{internshipId}/logbook/{logbookId}/validate",
            "POST /api/internships/{internshipId}/logbook/{logbookId}/reject",
            "GET /api/internships/{internshipId}/logbook",
            // §7.4 AI drafts + §3.3 bulk (own scope enforced server-side).
            "POST /api/internships/tasks/drafts/generate",
            "POST /api/internships/tasks/drafts/generate-from-text",
            "GET /api/internships/tasks/drafts",
            "POST /api/internships/tasks/drafts",
            "PUT /api/internships/tasks/drafts/{id}",
            "POST /api/internships/tasks/drafts/{id}/revise",
            "DELETE /api/internships/tasks/drafts/{id}",
            "POST /api/internships/tasks/drafts/bulk-add",
            // T03 student task classification (ST-TASK-03/04/05, D5/D5b):
            // INTERN-only participant surface — a supervisor or admin token
            // is refused at method level (403), proven per endpoint by
            // TaskCategoryIntegrationTest.staffIsForbidden; cross-student
            // ids are 404 inside the service. Listed here (not ADMIN_ONLY)
            // because no staff capability is involved, following the
            // candidates/me precedent above.
            "GET /api/internships/{id}/task-categories",
            "POST /api/internships/{id}/task-categories",
            "PUT /api/internships/task-categories/{categoryId}",
            "PUT /api/internships/task-categories/order",
            "DELETE /api/internships/task-categories/{categoryId}",
            "PUT /api/internships/tasks/{taskId}/category",
            "POST /api/internships/{id}/task-categories/suggest",
            "POST /api/internships/{id}/task-categories/apply",
            "POST /api/internships/task-categories/apply-batches/{batchId}/undo",
            // §5.2 scoped document surfaces (verify/attach stay admin-only).
            "POST /api/documents",
            "GET /api/documents/{id}",
            "GET /api/documents/{id}/download",
            "GET /api/documents/{id}/download-restricted",
            "GET /api/applications/{id}/documents",
            "POST /api/applications/{id}/documents",
            "GET /api/internships/{id}/documents",
            "POST /api/documents/{id}/validation",
            // Supervisor-side evaluations (participant/supervisorOf).
            "POST /api/internships/{internshipId}/evaluations",
            "GET /api/internships/{internshipId}/evaluations",
            "GET /api/evaluations/{evaluationId}",
            "POST /api/evaluations/{evaluationId}/scores",
            "GET /api/evaluations/{evaluationId}/scores",
            "POST /api/evaluations/{evaluationId}/task-reviews",
            "GET /api/evaluations/{evaluationId}/task-reviews",
            // Reference reads both roles share.
            "GET /api/evaluation-templates",
            "GET /api/evaluation-templates/{templateId}",
            "GET /api/evaluation-templates/{templateId}/criteria",
            // §3.3 scoped finance reads + scoped approve/reject surface.
            "GET /api/finance-cases",
            "GET /api/finance-cases/{id}",
            "POST /api/finance-cases/{id}/approve",
            "POST /api/finance-cases/{id}/reject",
            "GET /api/finance-cases/{id}/receipt",
            // §10 session self-management.
            "POST /api/auth/logout",
            "POST /api/auth/logout-all",
            "POST /api/auth/change-password",
            "PUT /api/users/me/locale",
            // §3.3 scoped internship reads.
            "GET /api/internships",
            "GET /api/internships/{id}",
            "GET /api/internships/{id}/assignments",
            "GET /api/internships/{id}/classification",
            // §8.1 own notifications; §3.3 own conversations.
            "GET /api/notifications",
            "GET /api/notifications/unread-count",
            "POST /api/notifications/{id}/read",
            "POST /api/notifications/read-all",
            "GET /api/conversations",
            "POST /api/conversations/group",
            "GET /api/conversations/{conversationId}",
            "POST /api/conversations/{conversationId}/members",
            "POST /api/conversations/{conversationId}/leave",
            "GET /api/conversations/{conversationId}/messages",
            "POST /api/conversations/{conversationId}/messages",
            "POST /api/conversations/{conversationId}/messages/with-attachment",
            "PATCH /api/conversations/messages/{messageId}",
            "DELETE /api/conversations/messages/{messageId}",
            "POST /api/conversations/{conversationId}/delivered",
            "POST /api/conversations/{conversationId}/read",
            "GET /api/conversations/unread/counts",
            "GET /api/conversations/messages/{messageId}/attachments",
            "GET /api/conversations/attachments/{attachmentId}/download",
            // §8.3 supervisor-scoped dashboard.
            "GET /api/reports/supervisor-summary");

    /** METHOD + Spring pattern for the anonymous surface (§3.4 layer 1 public list). */
    static final List<String> PUBLIC = List.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "POST /api/auth/back-office-login",
            "POST /api/auth/refresh",
            "GET /api/universities",
            "POST /api/public/applications",
            "POST /api/public/applications/track",
            "POST /api/public/applications/validate-identifiers",
            "POST /api/public/documents/validation",
            // OpenAPI docs (permitAll in SecurityConfig; no application data).
            "GET /swagger-ui.html",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User sup = userRepository.saveAndFlush(new User("sec_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(sup.getId(), sup.getEmail(), List.of("ROLE_SUPERVISOR"));
    }

    /** Validation-passing bodies for mutating admin endpoints (see class javadoc); null = no body. */
    private static String bodyFor(String entry) {
        String method = entry.substring(0, entry.indexOf(' '));
        if (method.equals("GET") || method.equals("DELETE")) {
            return null;
        }
        String path = entry.substring(entry.indexOf(' ') + 1);
        if (path.endsWith("/approve")) {
            return "{\"supervisorUserId\":null,\"departmentId\":\"" + U2 + "\"}";
        }
        if (entry.endsWith("/workflow/actions")) {
            return "{\"targetStepCode\":\"UNDER_REVIEW\",\"actionType\":\"VALIDATION\",\"comment\":\"probe\"}";
        }
        if (entry.endsWith("/from-application")) {
            return "{\"applicationId\":\"" + U1 + "\"}";
        }
        if (entry.endsWith("/manual")) {
            return "{\"candidateId\":\"" + U1 + "\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-06-05\",\"subject\":\"probe\"}";
        }
        if (entry.endsWith("/status-transitions")) {
            return "{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"probe\"}";
        }
        if (entry.endsWith("/dates")) {
            return "{\"startDate\":\"2026-01-05\",\"endDate\":\"2026-06-05\"}";
        }
        if (entry.endsWith("/assignments")) {
            return "{\"departmentId\":\"" + U1 + "\",\"supervisorId\":\"" + U2 + "\"}";
        }
        if (entry.endsWith("/verify") && entry.contains("internship-validation")) {
            return "{\"documentType\":\"REPORT\"}";
        }
        if (entry.endsWith("/decisions")) {
            return "{\"documentType\":\"REPORT\",\"decision\":\"VALIDATED\"}";
        }
        if (entry.endsWith("/verify")) {
            return "{\"status\":\"VERIFIED\"}";
        }
        if (entry.endsWith("/finance-cases")) {
            return "{\"internshipId\":\"" + U1 + "\"}";
        }
        if (entry.endsWith("/documents") && entry.contains("finance-cases")) {
            return "{\"documentId\":\"" + U2 + "\"}";
        }
        if (entry.contains("/documents/")) {
            return "{\"status\":\"VERIFIED\"}";
        }
        if (entry.endsWith("/documents") && entry.contains("/internships/")) {
            return "{\"documentId\":\"" + U2 + "\"}";
        }
        if (entry.endsWith("/intern-accounts")) {
            return "{\"email\":\"probeacct@test.tn\",\"role\":\"INTERN\"}";
        }
        if (entry.endsWith("/supervisors")) {
            return "{\"email\":\"newprobe@test.tn\"}";
        }
        if (entry.endsWith("/assign-candidate")) {
            return "{\"internshipId\":\"" + U2 + "\"}";
        }
        if (entry.contains("/reassign/")) {
            return "{\"newSupervisorUserId\":\"" + U1 + "\"}";
        }
        if (path.contains("/departments")) {
            return "{\"code\":\"PRB\",\"name\":\"Probe\"}";
        }
        if (path.contains("/employees")) {
            return "{\"employeeNumber\":\"PRB1\",\"firstName\":\"A\",\"lastName\":\"B\",\"departmentId\":\"" + U1 + "\"}";
        }
        if (path.contains("/evaluation-templates") || path.contains("/criteria")) {
            return "{\"name\":\"probe\"}";
        }
        if (method.equals("PUT") || method.equals("PATCH")) {
            return "{}";
        }
        return null;
    }

    private record Probe(String method, String path, String body) {
    }

    static Stream<Arguments> adminOnly() {
        String json = MediaType.APPLICATION_JSON_VALUE;
        return ADMIN_ONLY.stream().map(entry -> {
            int space = entry.indexOf(' ');
            String method = entry.substring(0, space);
            // Enum-typed path variables need a valid enum value (random UUIDs
            // fail conversion with 400 before security is even reached).
            String path = entry.substring(space + 1).replace("{documentType}", "REPORT");
            path = path.replaceAll("\\{[^}]+\\}", UUID.randomUUID().toString());
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q);
                path = path.substring(0, q);
            }
            return Arguments.of(method, path + query, bodyFor(entry), json);
        });
    }

    private org.springframework.test.web.servlet.RequestBuilder request(
            String method, String path, String body, String token) throws Exception {
        MockHttpServletRequestBuilder req = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }

    @ParameterizedTest(name = "SUPERVISOR {0} {1} → 403")
    @MethodSource("adminOnly")
    @DisplayName("supervisor is refused on every admin-only endpoint")
    void supervisorIsRefusedEverywhere(String method, String path, String body, String contentType) throws Exception {
        mockMvc.perform(request(method, path, body, supervisorToken))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "ANON {0} {1} → 401")
    @MethodSource("adminOnly")
    @DisplayName("anonymous is unauthenticated on every admin-only endpoint")
    void anonymousIsUnauthorizedEverywhere(String method, String path, String body, String contentType)
            throws Exception {
        mockMvc.perform(request(method, path, body, null))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("tripwire: every controller endpoint is classified in exactly one table")
    void everyControllerEndpointIsClassified() {
        Set<String> admin = stripped(ADMIN_ONLY);
        Set<String> allowed = stripped(SUPERVISOR_ALLOWED);
        Set<String> pub = stripped(PUBLIC);
        assertThat(admin).doesNotContainAnyElementsOf(allowed);
        assertThat(admin).doesNotContainAnyElementsOf(pub);
        assertThat(allowed).doesNotContainAnyElementsOf(pub);

        Set<String> actual = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            // Framework infra (BasicErrorController) is not product surface.
            if (method.getBeanType().getName().startsWith("org.springframework.")) {
                return;
            }
            Set<String> patterns = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues()
                    : Set.of();
            var httpMethods = info.getMethodsCondition().getMethods();
            assertThat(httpMethods).as("mapped HTTP methods for %s", method).isNotEmpty();
            assertThat(patterns).as("mapped paths for %s", method).isNotEmpty();
            for (var httpMethod : httpMethods) {
                for (String pattern : patterns) {
                    actual.add(httpMethod.name() + " " + pattern);
                }
            }
        });

        Set<String> classified = new TreeSet<>();
        classified.addAll(admin);
        classified.addAll(allowed);
        classified.addAll(pub);
        List<String> unknown = new ArrayList<>(actual);
        unknown.removeAll(classified);
        assertThat(unknown)
                .as("endpoints missing from all three hand-reviewed tables — classify per §3.3 first")
                .isEmpty();
    }

    private static Set<String> stripped(List<String> entries) {
        Set<String> out = new TreeSet<>();
        for (String entry : entries) {
            out.add(stripQuery(entry));
        }
        return out;
    }

    private static String stripQuery(String entry) {
        int q = entry.indexOf('?');
        return q >= 0 ? entry.substring(0, q) : entry;
    }
}
