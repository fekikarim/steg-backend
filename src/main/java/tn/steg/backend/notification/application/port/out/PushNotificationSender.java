package tn.steg.backend.notification.application.port.out;

import java.util.UUID;

/**
 * Outbound port for mobile push (Phase A10).
 * The default adapter is a no-op stub: wire FCM/APNs here later without
 * touching callers (store device tokens, then push per recipient).
 */
public interface PushNotificationSender {

    /**
     * Pushes a lightweight alert to a user's devices.
     *
     * @param userId  recipient
     * @param title   alert title
     * @param message alert body
     * @throws RuntimeException on any delivery failure
     */
    void send(UUID userId, String title, String message);
}
