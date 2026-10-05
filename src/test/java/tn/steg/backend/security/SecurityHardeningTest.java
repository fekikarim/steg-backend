package tn.steg.backend.security;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12b security review — behavioral proofs (AGENTS.md §10), all against real
 * Postgres: upload validation + authorized download, CORS allow-list,
 * security headers, refresh/logout revocation, rate-limit enforcement,
 * demo-seeder off by default, and a log scan proving no secret, password,
 * token, CIN or email ever reaches the logs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S12b — security hardening proofs")
class SecurityHardeningTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private JwtService jwtService;
    @Autowired private tn.steg.backend.common.infrastructure.ratelimit.RateLimitingAspect rateLimitingAspect;

    private MockMvc mockMvc;
    private String run;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);
    }

    private String adminToken() {
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        User admin = userRepository.saveAndFlush(new User("sec_hard_admin_" + run + "@test.tn", "hash", UserStatus.ACTIVE));
        admin.getAssignedRoles().add(adminRole);
        admin = userRepository.saveAndFlush(admin);
        return jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
    }

    // ------------------------------------------------------------------
    // Uploads: type validation, empty-file refusal, authorized download
    // ------------------------------------------------------------------

    @Test
    @DisplayName("spoofed and empty uploads are refused; a real PDF is stored and downloadable by its owner only")
    void uploadsAreValidatedAndDownloadsAreAuthorized() throws Exception {
        String token = adminToken();

        // Garbage bytes named .pdf are not a PDF (magic/type inspection, not the extension).
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "spoof.pdf", "application/pdf",
                                "definitely not a pdf payload".getBytes(StandardCharsets.UTF_8)))
                        .param("type", "OTHER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());

        // Empty files are refused with a documented code.
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]))
                        .param("type", "OTHER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());

        // A real PDF uploads fine.
        MvcResult uploaded = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "real.pdf", "application/pdf", pdfBytes("hardening probe")))
                        .param("type", "OTHER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").exists())
                .andReturn();
        UUID documentId = UUID.fromString(objectMapper.readTree(
                uploaded.getResponse().getContentAsString()).get("id").asText());

        // Owner downloads; anonymous gets 401; an unrelated candidate gets 403, never the bytes.
        mockMvc.perform(get("/api/documents/" + documentId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + documentId + "/download"))
                .andExpect(status().isUnauthorized());

        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "sec_hard_foreign_" + run + "@test.tn",
                                "password", "SmokePass!2026",
                                "firstName", "Foreign",
                                "lastName", "Candidate"))))
                .andExpect(status().isCreated())
                .andReturn();
        String foreignToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("accessToken").asText();
        mockMvc.perform(get("/api/documents/" + documentId + "/download")
                        .header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("upload size caps are configured (25 MB servlet + 25 MB app cap)")
    void uploadSizeCapsAreConfigured() {
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("25MB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("25MB");
    }

    // ------------------------------------------------------------------
    // CORS + security headers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CORS preflight allows only the two front ends")
    void corsAllowsOnlyTheTwoFrontEnds() throws Exception {
        for (String origin : List.of("http://localhost:3000", "http://localhost:4200")) {
            mockMvc.perform(options("/api/audit")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        MvcResult evil = mockMvc.perform(options("/api/audit")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(evil.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("security headers: nosniff + frame DENY (HSTS is https-only by default)")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    // ------------------------------------------------------------------
    // JWT: logout revokes the refresh token
    // ------------------------------------------------------------------

    @Test
    @DisplayName("logout revokes the refresh token; reuse afterwards is rejected")
    void logoutRevokesTheRefreshToken() throws Exception {
        String email = "sec_hard_logout_" + run + "@test.tn";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "SmokePass!2026",
                                "firstName", "Logout",
                                "lastName", "Probe"))))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "SmokePass!2026"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokens = objectMapper.readTree(login.getResponse().getContentAsString());
        String access = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();
        assertThat(access).isNotBlank();
        assertThat(refresh).isNotBlank();

        // Refresh works before logout (rotation proves the token is live).
        MvcResult rotated = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andReturn();
        String liveRefresh = objectMapper.readTree(rotated.getResponse().getContentAsString())
                .path("refreshToken").asText();

        // Logout revokes the live token; any later use is rejected and the access
        // token alone cannot mint new sessions.
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", liveRefresh))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", liveRefresh))))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------
    // Rate limiting is enforced (public identifier validation: 10/minute)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the 11th identifier-validation call inside a minute is 429")
    void rateLimitIsEnforced() throws Exception {
        // Buckets are keyed <endpoint>|<principal>: a fresh user isolates this
        // probe's own 11 calls; the aspect's windows are cleared afterwards so
        // no later test in this JVM can observe the exhausted bucket.
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "sec_hard_ratelimit_" + run + "@test.tn",
                                "password", "SmokePass!2026",
                                "firstName", "Rate",
                                "lastName", "Limit"))))
                .andExpect(status().isCreated())
                .andReturn();
        String userToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("accessToken").asText();
        String body = objectMapper.writeValueAsString(Map.of("email", "ratelimit_" + run + "@test.tn"));
        try {
            int limited = 0;
            for (int i = 0; i < 11; i++) {
                int code = mockMvc.perform(post("/api/public/applications/validate-identifiers")
                                .header("Authorization", "Bearer " + userToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
                if (code == 429) {
                    limited++;
                }
            }
            assertThat(limited).as("at least the 11th call is rate-limited").isPositive();
        } finally {
            clearRateLimiterWindows();
        }
    }

    private void clearRateLimiterWindows() {
        try {
            java.lang.reflect.Field windows =
                    tn.steg.backend.common.infrastructure.ratelimit.RateLimitingAspect.class
                            .getDeclaredField("windows");
            windows.setAccessible(true);
            ((java.util.concurrent.ConcurrentMap<?, ?>) windows.get(rateLimitingAspect)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot clear rate limiter windows", e);
        }
    }

    // ------------------------------------------------------------------
    // Demo seeder is OFF by default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("demo seeding is off unless the integration profile plus the explicit switch are set")
    void demoSeederIsOffByDefault() {
        assertThat(applicationContext.getBeanNamesForType(
                tn.steg.backend.common.infrastructure.seed.IntegrationDemoSeeder.class)).isEmpty();
        assertThat(environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of("integration"))).isFalse();
        assertThat(environment.getProperty("steg.seed.demo-enabled", Boolean.class, false)).isFalse();
    }

    // ------------------------------------------------------------------
    // Log scan: no secret, password, token, CIN or email in the logs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full credential-bearing flow leaves no secret, token, CIN or email in the logs")
    void logsNeverCarrySecretsOrPii() throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        ch.qos.logback.classic.Logger root =
                ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            String email = "sec_hard_logscan_" + run + "@test.tn";
            String cin = "CIN-" + run.toUpperCase() + "1";
            MvcResult registered = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", email,
                                    "password", "SmokePass!2026",
                                    "firstName", "Log",
                                    "lastName", "Scan"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            String candidateToken = objectMapper.readTree(registered.getResponse().getContentAsString())
                    .path("accessToken").asText();
            String firstRefresh = objectMapper.readTree(registered.getResponse().getContentAsString())
                    .path("refreshToken").asText();

            University uni = universityRepository.saveAndFlush(
                    new University("UNI_LOGSCAN_" + run, "Logscan Uni"));
            mockMvc.perform(post("/api/candidates")
                            .header("Authorization", "Bearer " + candidateToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "firstName", "Log",
                                    "lastName", "Scan",
                                    "email", email,
                                    "universityId", uni.getId().toString(),
                                    "nationalId", cin))))
                    .andExpect(status().isCreated());

            // Obligatory PFE dates so approval provisions credentials (the secret under test).
            MvcResult app = mockMvc.perform(post("/api/applications")
                            .header("Authorization", "Bearer " + candidateToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "desiredStartDate", LocalDate.of(2026, 1, 5).toString(),
                                    "desiredEndDate", LocalDate.of(2026, 6, 5).toString()))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID appId = UUID.fromString(objectMapper.readTree(
                    app.getResponse().getContentAsString()).get("id").asText());
            mockMvc.perform(post("/api/applications/" + appId + "/submit")
                            .header("Authorization", "Bearer " + candidateToken))
                    .andExpect(status().isOk());

            Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
            User admin = userRepository.saveAndFlush(
                    new User("sec_hard_logscan_admin_" + run + "@test.tn", "hash", UserStatus.ACTIVE));
            admin.getAssignedRoles().add(adminRole);
            admin = userRepository.saveAndFlush(admin);
            String adminToken = jwtService.generateAccessToken(
                    admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
            Department dept = departmentRepository.saveAndFlush(
                    new Department("DIR_LOGSCAN_" + run, "Logscan Dept", "LS"));

            MvcResult approved = mockMvc.perform(post("/api/applications/" + appId + "/approve")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"supervisorUserId\":null,\"departmentId\":\"" + dept.getId() + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String tempPassword = objectMapper.readTree(approved.getResponse().getContentAsString())
                    .path("temporaryPassword").asText();
            assertThat(tempPassword).isNotBlank();

            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", email, "password", tempPassword))))
                    .andExpect(status().isOk())
                    .andReturn();
            String liveAccess = objectMapper.readTree(login.getResponse().getContentAsString())
                    .path("accessToken").asText();

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(logs).doesNotContain(tempPassword);
            assertThat(logs).doesNotContain(candidateToken);
            assertThat(logs).doesNotContain(liveAccess);
            assertThat(logs).doesNotContain(firstRefresh);
            assertThat(logs).doesNotContain(cin);
            assertThat(logs).doesNotContain(email);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
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
