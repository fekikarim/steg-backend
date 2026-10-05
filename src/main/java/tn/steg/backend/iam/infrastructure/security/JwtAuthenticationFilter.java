package tn.steg.backend.iam.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.application.dto.ErrorEnvelope;
import tn.steg.backend.common.infrastructure.logging.TraceIdFilter;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isPublicEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // If an authentication is already present in SecurityContext (e.g. from tests or prior filter), proceed
        if (SecurityContextHolder.getContext().getAuthentication() != null &&
            SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required.", path);
            return;
        }

        try {
            Claims claims = jwtService.validateAccessToken(token);
            String email = claims.get("email", String.class);
            UUID userId = UUID.fromString(claims.getSubject());
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(item -> {
                        // If it starts with ROLE_ or is a permission (e.g. DOCUMENT_VIEW_RESTRICTED), keep or format appropriately
                        if (item.startsWith("ROLE_")) {
                            return new SimpleGrantedAuthority(item);
                        } else if (item.equals("ADMIN") || item.equals("HR") || item.equals("CANDIDATE")
                                || item.equals("SUPERVISOR") || item.equals("FINANCE") || item.equals("DIRECTOR")
                                || item.equals("INTERN")) {
                            return new SimpleGrantedAuthority("ROLE_" + item);
                        } else {
                            // Permission or custom authority
                            return new SimpleGrantedAuthority(item);
                        }
                    })
                    .toList();

            UserPrincipal principal = new UserPrincipal(userId, email, roles);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Session revocation (Task 3): access tokens are stateless, so the
            // account state is re-checked on EVERY request. A deactivated or
            // deleted account's token stops working immediately — not at
            // expiry — and the check never leaks WHY (same generic message
            // for unknown, disabled and locked accounts).
            var account = userRepository.findById(userId).orElse(null);
            if (account == null || !Boolean.TRUE.equals(account.getEnabled())
                    || account.getStatus() != UserStatus.ACTIVE) {
                SecurityContextHolder.clearContext();
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "The account is no longer active.", path);
                return;
            }
            // Credential versioning (V53): an admin password reset must end
            // the existing session even though the account stays active —
            // tokens issued before the reset are refused.
            java.time.Instant issuedAt = claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : null;
            if (account.getCredentialsUpdatedAt() != null
                    && (issuedAt == null || issuedAt.isBefore(account.getCredentialsUpdatedAt()))) {
                SecurityContextHolder.clearContext();
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "This session is no longer valid — sign in again.", path);
                return;
            }

            // Task E: enforce mustChangePassword
            Boolean mustChangePassword = claims.get("mustChangePassword", Boolean.class);
            if (Boolean.TRUE.equals(mustChangePassword) && !isPasswordChangeAllowedEndpoint(path)) {
                sendError(response, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", path);
                return;
            }

        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            log.debug("Expired JWT token on path {}: {}", path, ex.getMessage());
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The access token has expired.", path);
            return;
        } catch (io.jsonwebtoken.JwtException ex) {
            log.debug("Invalid JWT token on path {}: {}", path, ex.getMessage());
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The access token is invalid.", path);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/back-office-login")
                || path.startsWith("/api/auth/refresh")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/universities")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/info")
                // Phase A9: WS handshake carries the JWT via ?token=/Authorization
                // and is validated by JwtHandshakeInterceptor instead.
                || path.startsWith("/ws");
    }

    private boolean isPasswordChangeAllowedEndpoint(String path) {
        return path.equals("/api/auth/change-password")
                || path.startsWith("/api/auth/logout")
                || path.equals("/api/auth/me")
                || path.startsWith("/api/users/me");
    }

    private void sendError(HttpServletResponse response, int status, String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorEnvelope envelope = ErrorEnvelope.builder()
                .timestamp(Instant.now())
                .status(status)
                .error(status == 401 ? "Authentication Required" : "Access Denied")
                .message(message)
                .path(path)
                .traceId(resolveTraceId())
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}
