package tn.steg.backend.messaging.infrastructure.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the existing JWT access token during the WebSocket handshake.
 *
 * <p>Clients MUST prefer the {@code Authorization: Bearer &lt;token&gt;} header
 * (STOMP CONNECT native header, or HTTP header on the upgrade request).
 * The {@code ?token=} query parameter exists only as a fallback for
 * environments that cannot set headers on the upgrade request (plain
 * browser WebSocket, some SockJS/mobile clients).
 *
 * <p>Production safety: query strings are routinely captured in access logs,
 * proxies and browser history. Deployments MUST use {@code wss}, short-lived
 * access tokens, and access-log formats that exclude query strings; the query
 * fallback should be disabled at the edge where headers are always available.
 * This interceptor never logs token values — only the sanitized path.
 *
 * <p>On success the resolved {@link UserPrincipal} is stored in the handshake
 * attributes for the {@code PrincipalHandshakeHandler} and the STOMP
 * channel interceptor. On failure the handshake is rejected (401).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_ATTR = "STEG_WS_PRINCIPAL";

    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            // Never log request.getURI(): it may embed ?token=<jwt>.
            log.debug("WS handshake rejected: missing token (path={})", sanitizePath(request));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Claims claims = jwtService.validateAccessToken(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            UserPrincipal principal = new UserPrincipal(userId, email, roles != null ? roles : List.of());
            attributes.put(PRINCIPAL_ATTR, principal);
            return true;
        } catch (Exception e) {
            log.debug("WS handshake rejected: invalid token: {}", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    /**
     * Returns the request path without the query string for safe logging.
     */
    private String sanitizePath(ServerHttpRequest request) {
        try {
            String path = request.getURI().getPath();
            return path != null ? path : "/ws";
        } catch (Exception e) {
            return "/ws";
        }
    }

    private String extractToken(ServerHttpRequest request) {
        List<String> authHeaders = request.getHeaders().get("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
                    return header.substring("Bearer ".length());
                }
            }
        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String param = servletRequest.getServletRequest().getParameter("token");
            if (StringUtils.hasText(param)) {
                return param.startsWith("Bearer ") ? param.substring("Bearer ".length()) : param;
            }
        }
        // Fallback: parse raw query string (covers non-servlet runtimes)
        String query = request.getURI().getQuery();
        if (StringUtils.hasText(query)) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0 && pair.substring(0, idx).equals("token")) {
                    String value = pair.substring(idx + 1);
                    return value.startsWith("Bearer%20") ? value.substring("Bearer%20".length()) : value;
                }
            }
        }
        return null;
    }
}
