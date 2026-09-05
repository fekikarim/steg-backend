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
}
