package tn.steg.backend.messaging.interfaces.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.messaging.application.dto.MessageResponse;
import tn.steg.backend.messaging.application.dto.SendMessageRequest;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP inbound adapter for real-time messaging.
 *
 * <p>Clients send to {@code /app/conversations/{id}/send}; the server
 * persists via {@link MessagingService} (which re-validates active membership
 * on every SEND) and broadcasts the persisted message to
 * {@code /topic/conversations/{id}}. Failures are reported to the sender on
 * {@code /user/queue/errors} without leaking other conversations' existence.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MessagingStompController {

    private final MessagingService messagingService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/conversations/{conversationId}/send")
    public void handleSend(@DestinationVariable UUID conversationId,
                           @Payload SendMessageRequest payload,
                           Principal principal) {
        UserPrincipal actor = resolvePrincipal(principal);
        if (actor == null) {
            sendError(principal, "UNAUTHENTICATED", "Authentication required.");
            return;
        }
        try {
            MessageResponse saved = messagingService.sendMessage(conversationId, payload.content(), actor);
            messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, saved);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.debug("STOMP SEND denied for user {} on conv {}: {}", actor.getId(), conversationId, e.getMessage());
            sendError(principal, "ACCESS_DENIED", "You are not an active member of this conversation.");
        } catch (tn.steg.backend.common.domain.exception.ResourceNotFoundException e) {
            sendError(principal, "NOT_FOUND", "Conversation not found.");
        } catch (tn.steg.backend.common.domain.exception.BusinessRuleException e) {
            sendError(principal, e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("STOMP SEND failed for conv {}: ", conversationId, e);
            sendError(principal, "SEND_FAILED", "Could not deliver the message. Please retry.");
        }
    }

    private UserPrincipal resolvePrincipal(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        return null;
    }

    private void sendError(Principal principal, String code, String message) {
        if (principal == null || principal.getName() == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(
                principal.getName(), "/queue/errors", Map.of("code", code, "message", message));
    }
}
