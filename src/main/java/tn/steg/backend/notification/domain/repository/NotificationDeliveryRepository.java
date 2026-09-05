package tn.steg.backend.notification.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationDeliveryStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — NotificationDelivery persistence.
 */
public interface NotificationDeliveryRepository {
    Optional<NotificationDelivery> findById(UUID id);
    NotificationDelivery save(NotificationDelivery delivery);
    Page<NotificationDelivery> findInAppByRecipientId(UUID recipientId, Pageable pageable);
    Page<NotificationDelivery> findUnreadInAppByRecipientId(UUID recipientId, Pageable pageable);
    Optional<NotificationDelivery> findInAppByNotificationIdAndRecipientId(UUID notificationId, UUID recipientId);
    List<NotificationDelivery> findByNotificationId(UUID notificationId);
    long countUnreadInAppByRecipientId(UUID recipientId);
    List<NotificationDelivery> findRetryable(NotificationDeliveryStatus status, int maxAttempts, Instant now, Pageable pageable);
}
