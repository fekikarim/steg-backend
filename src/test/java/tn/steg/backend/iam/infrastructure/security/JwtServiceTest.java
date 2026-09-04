package tn.steg.backend.iam.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    private static final String TEST_SECRET = "a3F8kP2xNmQ7vBcYhJ6wLzR1uT4eI0oG5sDfHjKlMnPqXcVbNaWmEYr";
    private static final String TEST_ISSUER = "steg-platform";
    private static final long ACCESS_EXPIRATION_MINUTES = 15;
    private static final long REFRESH_EXPIRATION_DAYS = 7;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, TEST_ISSUER, ACCESS_EXPIRATION_MINUTES, REFRESH_EXPIRATION_DAYS);
    }

    @Nested
    @DisplayName("Access Token Generation & Validation")
    class AccessTokenTests {

        @Test
        @DisplayName("Should generate a valid access token")
        void generateValidAccessToken() {
            UUID userId = UUID.randomUUID();
            String email = "test@steg.com";
            List<String> roles = List.of("ROLE_CANDIDATE");

            String token = jwtService.generateAccessToken(userId, email, roles);

            assertThat(token).isNotBlank();
            Claims claims = jwtService.validateAccessToken(token);
            assertThat(claims.getSubject()).isEqualTo(userId.toString());
            assertThat(claims.get("email", String.class)).isEqualTo(email);
            assertThat(claims.get("roles", List.class)).containsExactly("ROLE_CANDIDATE");
            assertThat(claims.getIssuer()).isEqualTo(TEST_ISSUER);
            assertThat(claims.getId()).isNotBlank();
        }

        @Test
        @DisplayName("Should reject token signed with wrong secret")
        void rejectTokenWithWrongSecret() {
            JwtService wrongSecretService = new JwtService(
                    "wrong-secret-key-that-is-long-enough-for-hs512-algorithm!!!",
                    TEST_ISSUER, ACCESS_EXPIRATION_MINUTES, REFRESH_EXPIRATION_DAYS);

            String token = wrongSecretService.generateAccessToken(
                    UUID.randomUUID(), "test@steg.com", List.of("ROLE_CANDIDATE"));

            assertThatThrownBy(() -> jwtService.validateAccessToken(token))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("Should reject token with wrong issuer")
        void rejectTokenWithWrongIssuer() {
            JwtService wrongIssuerService = new JwtService(
                    TEST_SECRET, "wrong-issuer", ACCESS_EXPIRATION_MINUTES, REFRESH_EXPIRATION_DAYS);

            String token = wrongIssuerService.generateAccessToken(
                    UUID.randomUUID(), "test@steg.com", List.of("ROLE_CANDIDATE"));

            assertThatThrownBy(() -> jwtService.validateAccessToken(token))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("Should extract payload correctly")
        void extractPayloadCorrectly() {
            UUID userId = UUID.randomUUID();
            String email = "candidate@university.tn";
            List<String> roles = List.of("ROLE_CANDIDATE", "ROLE_INTERN");

            String token = jwtService.generateAccessToken(userId, email, roles);
            Claims claims = jwtService.validateAccessToken(token);
            var payload = jwtService.extractPayload(claims);

            assertThat(payload.getUserId()).isEqualTo(userId);
            assertThat(payload.getEmail()).isEqualTo(email);
            assertThat(payload.getRoles()).containsExactly("ROLE_CANDIDATE", "ROLE_INTERN");
            assertThat(payload.getJti()).isNotBlank();
            assertThat(payload.getExpiresAt()).isAfter(payload.getIssuedAt());
        }

        @Test
        @DisplayName("Should generate unique tokens each time")
        void generateUniqueTokens() {
            UUID userId = UUID.randomUUID();
            String email = "test@steg.com";

            String token1 = jwtService.generateAccessToken(userId, email, List.of("ROLE_CANDIDATE"));
            String token2 = jwtService.generateAccessToken(userId, email, List.of("ROLE_CANDIDATE"));

            assertThat(token1).isNotEqualTo(token2);
        }
    }

    @Nested
    @DisplayName("Refresh Token Operations")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should generate opaque refresh token")
        void generateOpaqueRefreshToken() {
            String token = jwtService.generateRefreshToken();
            assertThat(token).isNotBlank();
            assertThat(token).doesNotContain(".");
        }

        @Test
        @DisplayName("Should hash tokens consistently")
        void hashTokensConsistently() {
            String rawToken = jwtService.generateRefreshToken();
            String hash1 = jwtService.hashToken(rawToken);
            String hash2 = jwtService.hashToken(rawToken);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64); // SHA-256 hex = 64 chars
        }

        @Test
        @DisplayName("Should produce different hashes for different tokens")
        void differentHashesForDifferentTokens() {
            String token1 = jwtService.generateRefreshToken();
            String token2 = jwtService.generateRefreshToken();

            assertThat(jwtService.hashToken(token1)).isNotEqualTo(jwtService.hashToken(token2));
        }

        @Test
        @DisplayName("Should return correct expiration values")
        void returnCorrectExpirationValues() {
            assertThat(jwtService.getAccessTokenExpirationSeconds()).isEqualTo(15 * 60);
            assertThat(jwtService.getRefreshTokenExpirationSeconds()).isEqualTo(7 * 24 * 60 * 60);
        }
    }
}
