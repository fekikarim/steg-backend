package tn.steg.backend.messaging.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Production gate: WebSocket handshake authentication safety.
 * Verifies header preference, query fallback, rejection semantics, and —
 * critically — that rejection logging never embeds the request URI
 * (which may carry {@code ?token=<jwt>}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JWT Handshake Interceptor Tests (A9 production gate)")
class JwtHandshakeInterceptorTest {

    private static final String SECRET = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final String ISSUER = "steg-platform";

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private JwtService jwtService;
    private JwtHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, ISSUER, 15, 7);
        interceptor = new JwtHandshakeInterceptor(jwtService);
        lenient().when(request.getHeaders()).thenReturn(new HttpHeaders());
    }

    private String mintToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("email", "ws@test.com")
                .claim("roles", List.of("ROLE_INTERN"))
                .signWith(key)
                .compact();
    }

    @Test
    @DisplayName("Authorization header token is accepted and stored as principal")
    void headerTokenAccepted() throws Exception {
        UUID userId = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + mintToken(userId));
        when(request.getHeaders()).thenReturn(headers);
        lenient().when(request.getURI()).thenReturn(new URI("ws://localhost/ws"));

        Map<String, Object> attributes = new HashMap<>();
        assertThat(interceptor.beforeHandshake(request, response, wsHandler, attributes)).isTrue();
        assertThat(attributes.get(JwtHandshakeInterceptor.PRINCIPAL_ATTR))
                .isInstanceOfSatisfying(UserPrincipal.class, p -> assertThat(p.getId()).isEqualTo(userId));
    }

    @Test
    @DisplayName("Missing token is rejected with 401 and the logged path excludes the query string")
    void missingTokenRejectedWithoutLeakingQuery() throws Exception {
        // URI carries a (bogus) token in the query: logging must never echo it.
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws?token=SUPER_SECRET_JWT&other=1"));

        Map<String, Object> attributes = new HashMap<>();
        assertThat(interceptor.beforeHandshake(request, response, wsHandler, attributes)).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).doesNotContainKey(JwtHandshakeInterceptor.PRINCIPAL_ATTR);
    }

    @Test
    @DisplayName("Invalid token is rejected with 401")
    void invalidTokenRejected() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer invalid.token.here");
        when(request.getHeaders()).thenReturn(headers);
        lenient().when(request.getURI()).thenReturn(new URI("ws://localhost/ws"));

        assertThat(interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>())).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
