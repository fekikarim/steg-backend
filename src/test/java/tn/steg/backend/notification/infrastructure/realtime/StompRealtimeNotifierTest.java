package tn.steg.backend.notification.infrastructure.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tn.steg.backend.notification.application.dto.NotificationPayload;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * S9 STOMP routing (§8.1): pushes go to the recipient's personal user
 * destination only; a broker failure never propagates (the persisted IN_APP
 * row is the authority, the push is transport nicety).
 */
@DisplayName("S9 — STOMP realtime routing")
class StompRealtimeNotifierTest {

    private static NotificationPayload payload() {
        return new NotificationPayload(
                UUID.randomUUID(), "Title", "Body",
                tn.steg.backend.notification.domain.model.NotificationPriority.HIGH,
                "Internship", UUID.randomUUID(), Instant.now());
    }

    @Test
    @DisplayName("push addresses exactly the recipient user destination")
    void pushTargetsRecipientOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        StompRealtimeNotifier notifier = new StompRealtimeNotifier(template);

        NotificationPayload payload = payload();
        notifier.pushToUser("sup@steg.tn", payload);

        verify(template).convertAndSendToUser(
                eq("sup@steg.tn"), eq("/queue/notifications"), eq(payload));
    }

    @Test
    @DisplayName("broker failure is swallowed, never thrown")
    void brokerFailureNeverThrows() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("broker down"))
                .when(template)
                .convertAndSendToUser(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        StompRealtimeNotifier notifier = new StompRealtimeNotifier(template);

        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> notifier.pushToUser("sup@steg.tn", payload()));
    }
}
