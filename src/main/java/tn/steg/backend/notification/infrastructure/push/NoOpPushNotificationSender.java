package tn.steg.backend.notification.infrastructure.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tn.steg.backend.notification.application.port.out.PushNotificationSender;

import java.util.UUID;

/**
 * No-op PUSH stub (Phase A10).
 *
 * <p>Deliberately does nothing but log: there are no device tokens yet, and no
 * paid push SaaS is introduced for MVP. To go live, replace this bean with an
 * FCM/APNs implementation behind the same {@link PushNotificationSender} port
 * (add a device-token registry first) — callers require zero changes.
 */
@Slf4j
@Component
public class NoOpPushNotificationSender implements PushNotificationSender {

    @Override
    public void send(UUID userId, String title, String message) {
        log.debug("NoOp push (no FCM/APNs wired): user={} title={}", userId, title);
    }
}
