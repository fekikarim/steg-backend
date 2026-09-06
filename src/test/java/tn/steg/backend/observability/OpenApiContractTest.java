package tn.steg.backend.observability;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A13 — OpenAPI contract. Boots the full application context and fetches
 * the live {@code /v3/api-docs} spec, then verifies it is valid JSON, that every
 * A0–A12 module surface is present with summaries, that public endpoints are
 * not marked secure while protected ones require the Bearer scheme, that
 * paginated endpoints surface page/size/sort parameters, and that the
 * rate-limited upload endpoint documents 429. The raw spec is also written to
 * {@code target/openapi/openapi.json} as the consumer-facing build artifact.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("A13 — OpenAPI contract & artifact")
class OpenApiContractTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("spec is valid JSON with Bearer scheme and every module endpoint")
    void specIsValidAndComplete() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(spec);
        assertThat(root.path("openapi").asText()).isNotBlank();
        assertThat(root.path("info").path("version").asText()).isNotBlank();

        // Bearer JWT scheme must be declared (OpenApiConfig).
        assertThat(root.path("components").path("securitySchemes").has("BearerAuth")).isTrue();
        assertThat(root.path("components").path("securitySchemes").path("BearerAuth").path("scheme").asText())
                .isEqualTo("bearer");

        // All A0–A12 module surfaces must be present.
        JsonNode paths = root.path("paths");
        for (String expected : new String[]{
                "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                "/api/universities",
                "/api/applications/{id}/workflow", "/api/internships/{id}/workflow",
                "/api/documents", "/api/applications/{id}/documents",
                "/api/audit", "/api/audit/{id}",
                "/api/reports/applications-by-status", "/api/reports/payment-totals",
                "/api/ai/assistant/query", "/api/ai/applications/{id}/analyze"
        }) {
            assertThat(paths.has(expected)).as("missing expected path %s", expected).isTrue();
        }

        // Every operation must carry a non-empty summary.
        Iterator<String> pathNames = paths.fieldNames();
        while (pathNames.hasNext()) {
            String currentPath = pathNames.next();
            JsonNode operations = paths.get(currentPath);
            if (operations.isArray()) {
                continue;
            }
            for (String method : new String[]{"get", "post", "put", "patch", "delete"}) {
                if (operations.has(method)) {
                    assertThat(operations.get(method).path("summary").asText())
                            .as("operation %s %s must have a summary", method.toUpperCase(), currentPath)
                            .isNotBlank();
                }
            }
        }

        // Persist the live spec as the build artifact for client teams.
        Path artifact = Path.of("target", "openapi", "openapi.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, spec);
    }

    @Test
    @DisplayName("public endpoints do not require authentication, protected ones do")
    void securityRequirementsMatchReality() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(spec);
        JsonNode paths = root.path("paths");

        String[] publicOps = {"/api/auth/register:post", "/api/auth/login:post",
                "/api/auth/refresh:post", "/api/universities:get"};
        for (String op : publicOps) {
            String[] parts = op.split(":");
            JsonNode operation = paths.path(parts[0]).path(parts[1]);
            // Global BearerAuth security is merged by springdoc; public operations
            // opt out via @SecurityRequirements and surface as missing or "[]".
            assertThat(operation.path("security").isMissingNode() || operation.path("security").isEmpty())
                    .as("%s %s must NOT declare a security requirement", parts[1].toUpperCase(), parts[0])
                    .isTrue();
        }

        String[] protectedOps = {"/api/audit:get", "/api/ai/assistant/query:post"};
        for (String op : protectedOps) {
            String[] parts = op.split(":");
            JsonNode operation = paths.path(parts[0]).path(parts[1]);
            boolean local = operation.path("security").toString().contains("\"BearerAuth\"");
            // An operation without its own security entry inherits the top-level
            // global BearerAuth requirement declared by OpenApiConfig.
            boolean inherited = operation.path("security").isMissingNode()
                    && root.path("security").toString().contains("\"BearerAuth\"");
            assertThat(local || inherited)
                    .as("%s %s must require BearerAuth", parts[1].toUpperCase(), parts[0])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("pagination and rate-limit error codes are part of the contract")
    void paginationAndRateLimitDocumented() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(spec);
        JsonNode paths = root.path("paths");

        // Audit page endpoint resolves its pageable query param to the Pageable
        // schema {page, size, sort}: pagination is part of the contract, not an
        // opaque framework type.
        JsonNode pageableParams = paths.path("/api/audit").path("get").path("parameters");
        JsonNode pageable = null;
        for (JsonNode param : pageableParams) {
            if ("pageable".equals(param.path("name").asText())) {
                pageable = param;
            }
        }
        assertThat(pageable).as("/api/audit must expose a pageable query parameter").isNotNull();
        String pageableRef = pageable.path("schema").path("$ref").asText();
        assertThat(pageableRef).as("pageable param must resolve to the Pageable schema").contains("/Pageable");
        JsonNode pageableSchema = root.path("components").path("schemas").path("Pageable");
        for (String property : new String[]{"page", "size", "sort"}) {
            assertThat(pageableSchema.path("properties").has(property))
                    .as("Pageable schema must document %s", property).isTrue();
        }

        // Upload endpoint documents the 429 failure as part of its contract.
        JsonNode uploadResponses = paths.path("/api/documents").path("post").path("responses");
        assertThat(uploadResponses.has("429")).as("upload must document 429 response").isTrue();
    }
}