package tn.steg.backend.messaging.infrastructure.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.messaging.application.MessagingService;

import java.util.List;
import java.util.UUID;

/**
 * Authenticates every STOMP frame, not just the handshake.
 *
 * <p>On {@code CONNECT} the JWT is taken from the native {@code Authorization}
 * header (or the handshake principal stored by {@link JwtHandshakeInterceptor})
 * and the resulting authentication is attached to the accessor, so subsequent
 * frames carry {@code getUser()}.
 *
 * <p>On {@code SUBSCRIBE} to {@code /topic/conversations/{id}} the interceptor
 * additionally enforces conversation visibility (active membership, including
 * the revoke-mode GROUP policy) via {@link MessagingService}. Without this,
 * anyone guessing a conversation id could subscribe to its broadcast topic and
 * receive messages they are not entitled to read. Per-message {@code SEND}
 * authorization still happens in {@code MessagingStompController}/
 * {@code MessagingService} on every frame.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String CONVERSATION_TOPIC_PREFIX = "/topic/conversations/";

    private final JwtService jwtService;
    private final MessagingService messagingService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            UsernamePasswordAuthenticationToken authentication = resolveAuthentication(accessor);
            if (authentication == null) {
                log.debug("STOMP CONNECT rejected: missing/invalid token");
                throw new IllegalArgumentException("Missing or invalid Authorization token for STOMP CONNECT.");
            }
            accessor.setUser(authentication);
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            UUID conversationId = conversationTopic(accessor.getDestination());
            if (conversationId != null) {
                UserPrincipal principal = currentPrincipal(accessor);
                if (principal == null
                        || !messagingService.isConversationVisible(conversationId, principal.getId())) {
                    // Generic message: unknown ids and forbidden ids are
                    // indistinguishable to the subscriber.
                    log.debug("STOMP SUBSCRIBE denied for conversation topic");
                    throw new AccessDeniedException("You are not an active member of this conversation.");
                }
            }
        }
        return message;
    }

    private UUID conversationTopic(String destination) {
        if (!StringUtils.hasText(destination) || !destination.startsWith(CONVERSATION_TOPIC_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(destination.substring(CONVERSATION_TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UserPrincipal currentPrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        if (accessor.getUser() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }

    private UsernamePasswordAuthenticationToken resolveAuthentication(StompHeaderAccessor accessor) {
        // 1. Native Authorization header on the CONNECT frame
        String token = null;
        List<String> authHeaders = accessor.getNativeHeader("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
                    token = header.substring("Bearer ".length());
                    break;
                }
            }
        }
        // 2. Handshake principal (token already validated during HTTP upgrade)
        if (token == null) {
            Object sessionPrincipal = accessor.getSessionAttributes() != null
                    ? accessor.getSessionAttributes().get(JwtHandshakeInterceptor.PRINCIPAL_ATTR)
                    : null;
            if (sessionPrincipal instanceof UserPrincipal principal) {
                return toAuthentication(principal);
            }
            // 3. Already-authenticated user (reconnect within same session)
            if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken existing) {
                return existing;
            }
            return null;
        }

        try {
            Claims claims = jwtService.validateAccessToken(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            return toAuthentication(new UserPrincipal(userId, email, roles != null ? roles : List.of()));
        } catch (Exception e) {
            log.debug("STOMP token validation failed: {}", e.getMessage());
            return null;
        }
    }

    private UsernamePasswordAuthenticationToken toAuthentication(UserPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.getRoles().stream()
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
