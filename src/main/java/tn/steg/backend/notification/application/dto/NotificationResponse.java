package tn.steg.backend.notification.application.dto;

import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one recipient's IN_APP delivery (IN_APP exists for every
 * notification, so listing deliveries == listing notifications for the user).
 */
public record NotificationResponse(
        UUID id,
        String title,
        String message,
        NotificationPriority priority,
        String relatedEntityType,
        UUID relatedEntityId,
        Instant createdAt,
        boolean read,
        Instant readAt
) {
    public static NotificationResponse from(Notification notification, NotificationDelivery inAppDelivery) {
        boolean read = inAppDelivery.getReadAt() != null;
        return new NotificationResponse(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getPriority(),
                notification.getRelatedEntityType(),
                notification.getRelatedEntityId(),
                notification.getCreatedAt(),
                read,
                inAppDelivery.getReadAt());
    }
}
