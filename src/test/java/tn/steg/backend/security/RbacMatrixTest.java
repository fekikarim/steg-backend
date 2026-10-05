package tn.steg.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E9 — RBAC matrix integration test.
 *
 * <p>Back Office is ADMIN + SUPERVISOR only; HR, FINANCE and DIRECTOR roles
 * were removed permanently (V34). Remaining system roles: ADMIN, SUPERVISOR,
 * CANDIDATE, INTERN.
 *
 * <p>Strategy:
 * <ul>
 *   <li>403 → role is authenticated but lacks the required permission.</li>
 *   <li>404 → role is authorized (passed the gate) but the resource doesn't exist (random UUID).</li>
 *   <li>401 → should never happen for any row in this table (all tokens are valid).</li>
 * </ul>
 *
 * <p>This test verifies structural authorization topology, not business logic.
 * Business-logic rejections (state transitions, IDOR probes) are covered by
 * {@link CrossModuleIdorTest} and {@link StateMachineRejectionTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E9 — RBAC Matrix: roles × endpoint domains")
class RbacMatrixTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    // Tokens for each live system role
    private String adminToken;
    private String supervisorToken;
    private String candidateToken;
    private String internToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        adminToken     = token("rbac_admin_"     + suffix + "@test.tn", "ROLE_ADMIN");
        supervisorToken = token("rbac_sup_"       + suffix + "@test.tn", "ROLE_SUPERVISOR");
        candidateToken = token("rbac_cand_"       + suffix + "@test.tn", "ROLE_CANDIDATE");
        internToken    = token("rbac_intern_"     + suffix + "@test.tn", "ROLE_INTERN");
    }

    private String token(String email, String role) {
        User u = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
        return jwtService.generateAccessToken(u.getId(), u.getEmail(), List.of(role));
    }

    // -------------------------------------------------------------------------
    // /api/audit — ADMIN only
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/audit — ADMIN only")
    class AuditEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN",      true),
                Arguments.of("SUPERVISOR", false),
                Arguments.of("CANDIDATE",  false),
                Arguments.of("INTERN",     false)
            );
        }

        @ParameterizedTest(name = "{0} → authorized={1}")
        @MethodSource("matrix")
        @DisplayName("role access")
        void check(String role, boolean authorized) throws Exception {
            ResultMatcher expected = authorized ? status().isOk() : status().isForbidden();
            mockMvc.perform(get("/api/audit")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(expected);
        }
    }

    // -------------------------------------------------------------------------
    // /api/finance-cases — ADMIN + SUPERVISOR (supervisor sees assigned only)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/finance-cases — ADMIN and SUPERVISOR")
    class FinanceCasesEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN",      true),   // hasAnyRole('ADMIN', 'SUPERVISOR')
                Arguments.of("SUPERVISOR", true),   // scoped to assigned cases in service
                Arguments.of("CANDIDATE",  false),
                Arguments.of("INTERN",     false)
            );
        }

        @ParameterizedTest(name = "{0} → authorized={1}")
        @MethodSource("matrix")
        @DisplayName("role access")
        void check(String role, boolean authorized) throws Exception {
            ResultMatcher expected = authorized ? status().isOk() : status().isForbidden();
            mockMvc.perform(get("/api/finance-cases")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(expected);
        }
    }

    // -------------------------------------------------------------------------
    // /api/candidates — ADMIN + SUPERVISOR
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/candidates — ADMIN and SUPERVISOR")
    class CandidatesEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN",      true),
                Arguments.of("SUPERVISOR", true),
                Arguments.of("CANDIDATE",  false),
                Arguments.of("INTERN",     false)
            );
        }

        @ParameterizedTest(name = "{0} → authorized={1}")
        @MethodSource("matrix")
        @DisplayName("role access")
        void check(String role, boolean authorized) throws Exception {
            ResultMatcher expected = authorized ? status().isOk() : status().isForbidden();
            mockMvc.perform(get("/api/candidates")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(expected);
        }
    }

    // -------------------------------------------------------------------------
    // /api/internships — ADMIN, SUPERVISOR see list; CANDIDATE/INTERN see 403
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/internships — ADMIN, SUPERVISOR")
    class InternshipsListEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN",      true),
                Arguments.of("SUPERVISOR", true),
                Arguments.of("CANDIDATE",  false),
                Arguments.of("INTERN",     false)
            );
        }

        @ParameterizedTest(name = "{0} → authorized={1}")
        @MethodSource("matrix")
        @DisplayName("role access")
        void check(String role, boolean authorized) throws Exception {
            ResultMatcher expected = authorized ? status().isOk() : status().isForbidden();
            mockMvc.perform(get("/api/internships")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(expected);
        }
    }

    // -------------------------------------------------------------------------
    // Obsolete roles (HR/FINANCE/DIRECTOR) are rejected everywhere, even with
    // a structurally valid JWT — no endpoint grants them anymore (V34).
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Obsolete HR/FINANCE/DIRECTOR tokens → 403 on staff endpoints")
    class ObsoleteRolesRejected {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("/api/audit", "ROLE_HR"),
                Arguments.of("/api/audit", "ROLE_FINANCE"),
                Arguments.of("/api/audit", "ROLE_DIRECTOR"),
                Arguments.of("/api/finance-cases", "ROLE_HR"),
                Arguments.of("/api/finance-cases", "ROLE_FINANCE"),
                Arguments.of("/api/finance-cases", "ROLE_DIRECTOR"),
                Arguments.of("/api/candidates", "ROLE_HR"),
                Arguments.of("/api/candidates", "ROLE_FINANCE"),
                Arguments.of("/api/candidates", "ROLE_DIRECTOR"),
                Arguments.of("/api/internships", "ROLE_HR"),
                Arguments.of("/api/internships", "ROLE_FINANCE"),
                Arguments.of("/api/internships", "ROLE_DIRECTOR")
            );
        }

        @ParameterizedTest(name = "{1} on {0} → 403")
        @MethodSource("matrix")
        @DisplayName("obsolete role rejected")
        void check(String path, String role) throws Exception {
            String email = "rbac_obs_" + UUID.randomUUID().toString().substring(0, 8) + "@test.tn";
            User u = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
            String staleToken = jwtService.generateAccessToken(u.getId(), u.getEmail(), List.of(role));
            mockMvc.perform(get(path)
                    .header("Authorization", "Bearer " + staleToken))
                    .andExpect(status().isForbidden());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/notifications — authenticated users see their own stream;
    //   each role gets 200 (own notifications, possibly empty).
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/notifications — all authenticated roles")
    class NotificationsEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN"),
                Arguments.of("SUPERVISOR"),
                Arguments.of("CANDIDATE"),
                Arguments.of("INTERN")
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("matrix")
        @DisplayName("all roles authorized")
        void check(String role) throws Exception {
            mockMvc.perform(get("/api/notifications")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/conversations — all authenticated users (isAuthenticated());
    //   each role sees their own conversation list (may be empty).
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/conversations — all authenticated roles")
    class MessagingConversationsEndpoint {

        static Stream<Arguments> matrix() {
            return Stream.of(
                Arguments.of("ADMIN"),
                Arguments.of("SUPERVISOR"),
                Arguments.of("CANDIDATE"),
                Arguments.of("INTERN")
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("matrix")
        @DisplayName("all roles authorized")
        void check(String role) throws Exception {
            // Endpoint is @PreAuthorize("isAuthenticated()") — all roles receive 200
            // (their own conversation list, possibly empty).
            mockMvc.perform(get("/api/conversations")
                    .header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------------------
    // Unauthenticated access must always return 401, never 403 or 200
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Anonymous access → always 401")
    class AnonymousAccess {

        static Stream<Arguments> endpoints() {
            return Stream.of(
                Arguments.of("/api/audit"),
                Arguments.of("/api/finance-cases"),
                Arguments.of("/api/candidates"),
                Arguments.of("/api/internships"),
                Arguments.of("/api/notifications"),
                Arguments.of("/api/conversations")
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("endpoints")
        @DisplayName("no token → 401")
        void check(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String tokenFor(String role) {
        return switch (role) {
            case "ADMIN"      -> adminToken;
            case "SUPERVISOR" -> supervisorToken;
            case "CANDIDATE"  -> candidateToken;
            case "INTERN"     -> internToken;
            default           -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }
}
