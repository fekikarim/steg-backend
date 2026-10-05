package tn.steg.backend.iam.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 3 — SESSION REVOCATION on account lifecycle events, proven against
 * real Postgres through the real HTTP endpoints:
 *
 * <ul>
 *   <li><b>deactivated</b> (status/enabled change): the refresh token is
 *       refused and the still-unexpired ACCESS token stops working at once —
 *       the JWT filter re-checks the account state on every request;</li>
 *   <li><b>deleted</b>: same, the account is gone;</li>
 *   <li><b>password reset</b> (admin): refresh tokens revoked AND the old
 *       access token stops working immediately via the V53 credential version
 *       (tokens issued before the reset are refused), while the account
 *       itself stays ACTIVE for the temporary-password login.</li>
 * </ul>
 */
@SpringBootTest(properties = "steg.test.context-isolated=session-revocation")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Task 3 — deactivation, deletion and password reset end every live session")
class SessionRevocationIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private tn.steg.backend.common.infrastructure.ratelimit.RateLimitingAspect rateLimitingAspect;

    /**
     * Every login attempt in this class is deliberate (wrong password after a
     * reset). The login rate limiter buckets by client IP and MockMvc always
     * reports 127.0.0.1, so without this the class would leave the shared
     * suite-wide bucket full and break unrelated login tests — the same trap
     * {@code SecurityHardeningTest} already handles.
     */
    @AfterEach
    void clearRateLimiterWindows() {
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

    private MockMvc mockMvc;
    private String adminToken;

    /** The intern's own strong password after the forced rotation (≥16 chars, 4 classes). */
    private static final String NEW_PASSWORD = "N3w!InternPassw0rd#26";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        User admin = userRepository.saveAndFlush(new User(
                "revocation_admin_" + UUID.randomUUID().toString().substring(0, 8) + "@test.tn",
                "hash", UserStatus.ACTIVE));
        admin.getAssignedRoles().add(adminRole);
        admin = userRepository.saveAndFlush(admin);
        adminToken = jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
    }

    /** Creates a real mobile account through the admin API and logs into it. */
    private record AccountSession(UUID accountId, String email, String accessToken, String refreshToken, String password) {
    }

    private AccountSession createAndLogin(String label) throws Exception {
        String email = label + "_" + UUID.randomUUID().toString().substring(0, 8) + "@steg.tn";
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

        var created = mockMvc.perform(post("/api/intern-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"INTERN\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        // The create response carries the ONE-TIME password only (no id, no
        // second disclosure) — resolve the id via the repository, the password
        // from the response, exactly as the back office does it.
        UUID id = userRepository.findByEmail(email).orElseThrow().getId();
        String oneTimePassword = json.readTree(created.getResponse().getContentAsString())
                .path("temporaryPassword").asText();
        assertThat(oneTimePassword).isNotBlank();

        // Complete the forced first-login rotation (AGENTS.md §5.3), so the
        // session under test is a FULLY USABLE one: with mustChangePassword
        // left on, the JWT filter answers 403 PASSWORD_CHANGE_REQUIRED and the
        // 401 from the account-state check could never be observed.
        var oneTimeLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + oneTimePassword + "\"}"))
                .andReturn();
        assertThat(oneTimeLogin.getResponse().getStatus()).isEqualTo(200);
        String oneTimeToken = json.readTree(oneTimeLogin.getResponse().getContentAsString())
                .path("accessToken").asText();
        var change = mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + oneTimeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + oneTimePassword + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);

        var login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        var tree = json.readTree(login.getResponse().getContentAsString());

        // Sanity: the access token really works before the lifecycle event —
        // otherwise "it stopped working" would prove nothing.
        assertThat(mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + tree.path("accessToken").asText()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        return new AccountSession(id, email, tree.path("accessToken").asText(),
                tree.path("refreshToken").asText(), NEW_PASSWORD);
    }

    private int accessStatus(AccountSession session) throws Exception {
        return mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn().getResponse().getStatus();
    }

    private int refreshStatus(AccountSession session) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session.refreshToken() + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("deactivating an account revokes its refresh token and kills its access token immediately")
    void deactivationEndsTheWholeSession() throws Exception {
        AccountSession session = createAndLogin("deact");

        // The endpoint takes query parameters (status / enabled), not a JSON body.
        var deactivate = mockMvc.perform(patch("/api/intern-accounts/" + session.accountId() + "/status")
                        .param("status", "INACTIVE")
                        .param("enabled", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(deactivate.getResponse().getStatus()).isEqualTo(200);

        assertThat(accessStatus(session)).isEqualTo(401);
        assertThat(refreshStatus(session)).isNotIn(200, 201);
    }

    @Test
    @DisplayName("deleting an account revokes its refresh token and kills its access token immediately")
    void deletionEndsTheWholeSession() throws Exception {
        AccountSession session = createAndLogin("del");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/intern-accounts/" + session.accountId())
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();

        assertThat(accessStatus(session)).isEqualTo(401);
        assertThat(refreshStatus(session)).isNotIn(200, 201);
    }

    @Test
    @DisplayName("an admin password reset revokes sessions: old access token 401 (credential version), old refresh refused, old password dead")
    void passwordResetEndsTheWholeSession() throws Exception {
        AccountSession session = createAndLogin("reset");

        var reset = mockMvc.perform(post("/api/intern-accounts/" + session.accountId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        String temporaryPassword = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(reset.getResponse().getContentAsString()).path("temporaryPassword").asText();

        // Old access token: refused NOW (V53 credential version), not at expiry.
        assertThat(accessStatus(session)).isEqualTo(401);
        // Old refresh token: revoked.
        assertThat(refreshStatus(session)).isNotIn(200, 201);
        // Old password: dead. New temporary password: works, forces a change.
        // (422 = AUTHENTICATION_FAILED: this codebase maps a refused credential
        // check to 422, not 401 — see AuthenticationFailedException.)
        var oldLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + session.email() + "\",\"password\":\"" + session.password() + "\"}"))
                .andReturn();
        assertThat(oldLogin.getResponse().getStatus()).isEqualTo(422);

        var newLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + session.email() + "\",\"password\":\"" + temporaryPassword + "\"}"))
                .andReturn();
        assertThat(newLogin.getResponse().getStatus()).isEqualTo(200);
        assertThat(newLogin.getResponse().getContentAsString()).contains("mustChangePassword\":true");
    }
}
