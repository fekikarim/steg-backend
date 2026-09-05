package tn.steg.backend.notification.application.dto;

import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import java.time.Instant;
import java.util.UUID;

/**
 * Live WS push shape for a notification (personal
 * {@code /user/queue/notifications} destination). Field set is locked by
 * contract tests — keep in sync with clients.
 */
public record NotificationPayload(
        UUID notificationId,
        String title,
        String message,
        NotificationPriority priority,
        String relatedEntityType,
        UUID relatedEntityId,
        Instant createdAt
) {
    public static NotificationPayload from(Notification notification) {
        return new NotificationPayload(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getPriority(),
                notification.getRelatedEntityType(),
                notification.getRelatedEntityId(),
                notification.getCreatedAt());
    }
}
