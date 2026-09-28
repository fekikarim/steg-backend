package tn.steg.backend.notification.domain.repository;

import tn.steg.backend.notification.domain.model.Notification;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — Notification persistence.
 */
public interface NotificationRepository {
    Optional<Notification> findById(UUID id);
    Notification save(Notification notification);
    /**
     * Finds a previously dispatched notification by its idempotency key.
     * Used for exactly-once confirmations (e.g. one email per submission).
     */
    Optional<Notification> findByDedupeKey(String dedupeKey);
}
