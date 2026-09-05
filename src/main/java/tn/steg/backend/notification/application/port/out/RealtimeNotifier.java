package tn.steg.backend.notification.application.port.out;

import tn.steg.backend.notification.application.dto.NotificationPayload;

/**
 * Outbound port for live in-app push over the Phase A9 STOMP broker
 * (personal {@code /user/queue/notifications} destination).
 */
public interface RealtimeNotifier {

    /**
     * Pushes a payload to one connected user. Best-effort: implementations
     * must not throw for offline users (the persisted IN_APP delivery is the
     * authority; this is only the live signal).
     *
     * @param recipientEmail principal name the WS session authenticated with
     * @param payload        notification payload
     */
    void pushToUser(String recipientEmail, NotificationPayload payload);
}
