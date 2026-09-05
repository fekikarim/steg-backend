package tn.steg.backend.messaging.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.messaging.infrastructure.security.JwtHandshakeInterceptor;
import tn.steg.backend.messaging.infrastructure.security.StompAuthChannelInterceptor;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP-over-WebSocket transport for Phase A9 real-time messaging.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>Handshake: {@code /ws} (plain WebSocket + SockJS fallback).</li>
 *   <li>Client sends: {@code /app/conversations/{id}/send}.</li>
 *   <li>Server broadcasts: {@code /topic/conversations/{id}}.</li>
 *   <li>Errors: {@code /user/queue/errors}.</li>
 * </ul>
 * Authentication is JWT-based (see {@link JwtHandshakeInterceptor} +
 * {@link StompAuthChannelInterceptor}); membership is re-validated on every
 * SEND in the STOMP controller/service, never only at handshake time.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${steg.cors.allowed-origins:http://localhost:3000,http://localhost:4200}")
    private String allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler() {
            @Override
            protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                              Map<String, Object> attributes) {
                Object principal = attributes.get(JwtHandshakeInterceptor.PRINCIPAL_ATTR);
                if (principal instanceof UserPrincipal userPrincipal) {
                    var authorities = userPrincipal.getRoles().stream()
                            .map(r -> r.startsWith("ROLE_") ? r
                                    : "ROLE_" + r)
                            .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                            .toList();
                    return new UsernamePasswordAuthenticationToken(userPrincipal, null, authorities);
                }
                return super.determineUser(request, wsHandler, attributes);
            }
        };

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(handshakeHandler);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
