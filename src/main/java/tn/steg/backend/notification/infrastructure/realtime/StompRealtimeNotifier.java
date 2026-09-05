package tn.steg.backend.notification.infrastructure.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tn.steg.backend.notification.application.dto.NotificationPayload;
import tn.steg.backend.notification.application.port.out.RealtimeNotifier;

/**
 * Pushes IN_APP notifications to the recipient's personal
 * {@code /user/queue/notifications} STOMP destination, reusing the Phase A9
 * broker. Resolution is by principal name (the WS session authenticates as the
 * user's email); offline users simply miss the live signal and converge via
 * REST history — hence best-effort, never throwing for delivery purposes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompRealtimeNotifier implements RealtimeNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void pushToUser(String recipientEmail, NotificationPayload payload) {
        try {
            messagingTemplate.convertAndSendToUser(recipientEmail, "/queue/notifications", payload);
        } catch (Exception e) {
            log.warn("Realtime notification push failed for {}: {}", recipientEmail, e.getMessage());
        }
    }
}
