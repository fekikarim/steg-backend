package tn.steg.backend.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.application.dto.LoginRequest;
import tn.steg.backend.iam.application.dto.RefreshRequest;
import tn.steg.backend.iam.application.dto.RegisterRequest;
import tn.steg.backend.iam.infrastructure.persistence.RefreshTokenRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private static final String TEST_PASSWORD = "test-password-123";
    private static final String WRONG_PASSWORD = "wrong-password-999";
    private static final String OTHER_PASSWORD = "other-password-456";

    private MockMvc mockMvc;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        registerRequest = RegisterRequest.builder()
                .email("candidate@test.tn")
                .password(TEST_PASSWORD)
                .firstName("Ahmed")
                .lastName("Ben Ali")
                .phone("+216 71 123 456")
                .build();

        loginRequest = LoginRequest.builder()
                .email("candidate@test.tn")
                .password(TEST_PASSWORD)
                .build();
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterTests {

        @Test
        @DisplayName("Should register a new candidate and return tokens")
        void registerSuccess() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900));
        }

        @Test
        @DisplayName("Should reject duplicate email registration")
        void registerDuplicateEmail() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));
        }

        @Test
        @DisplayName("Should reject registration with invalid email")
        void registerInvalidEmail() throws Exception {
            RegisterRequest invalid = RegisterRequest.builder()
                    .email("not-an-email")
                    .password(TEST_PASSWORD)
                    .firstName("Test")
                    .lastName("User")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject registration with short password")
        void registerShortPassword() throws Exception {
            RegisterRequest invalid = RegisterRequest.builder()
                    .email("test@test.tn")
                    .password("short1")
                    .firstName("Test")
                    .lastName("User")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginTests {

        @Test
        @DisplayName("Should login with valid credentials")
        void loginSuccess() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        }

        @Test
        @DisplayName("Should reject login with wrong password")
        void loginWrongPassword() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isCreated());

            LoginRequest wrongPassword = LoginRequest.builder()
                    .email("candidate@test.tn")
                    .password(WRONG_PASSWORD)
                    .build();

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongPassword)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));
        }

        @Test
        @DisplayName("Should reject login with non-existent email")
        void loginNonExistentEmail() throws Exception {
            LoginRequest nonExistent = LoginRequest.builder()
                    .email("nobody@test.tn")
                    .password(OTHER_PASSWORD)
                    .build();

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(nonExistent)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class RefreshTests {

        @Test
        @DisplayName("Should refresh tokens with valid refresh token")
        void refreshSuccess() throws Exception {
            String responseBody = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andReturn().getResponse().getContentAsString();

            Map<String, Object> tokens = objectMapper.readValue(responseBody, Map.class);
            String refreshToken = (String) tokens.get("refreshToken");

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        }

        @Test
        @DisplayName("Should reject refresh with invalid token")
        void refreshInvalidToken() throws Exception {
            RefreshRequest invalid = new RefreshRequest("invalid-token-value");

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }

        @Test
        @DisplayName("Should reject reuse of revoked refresh token")
        void refreshRevokedToken() throws Exception {
            String responseBody = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andReturn().getResponse().getContentAsString();

            Map<String, Object> tokens = objectMapper.readValue(responseBody, Map.class);
            String refreshToken = (String) tokens.get("refreshToken");

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }
    }

    @Nested
    @DisplayName("Protected Endpoints")
    class ProtectedEndpointTests {

        @Test
        @DisplayName("Should reject unauthenticated request to protected endpoint")
        void rejectUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/auth/logout-all"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should accept authenticated request")
        void acceptAuthenticated() throws Exception {
            String responseBody = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andReturn().getResponse().getContentAsString();

            Map<String, Object> tokens = objectMapper.readValue(responseBody, Map.class);
            String accessToken = (String) tokens.get("accessToken");

            mockMvc.perform(post("/api/auth/logout-all")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("Account Lockout")
    class LockoutTests {

        @Test
        @DisplayName("Should lock account after 5 failed attempts")
        void lockAccountAfterFailedAttempts() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isCreated());

            LoginRequest wrongPassword = LoginRequest.builder()
                    .email("candidate@test.tn")
                    .password(WRONG_PASSWORD)
                    .build();

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(wrongPassword)))
                        .andExpect(status().isUnprocessableEntity());
            }

            LoginRequest correctPassword = LoginRequest.builder()
                    .email("candidate@test.tn")
                    .password(TEST_PASSWORD)
                    .build();

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(correctPassword)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
        }
    }
}
