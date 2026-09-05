package tn.steg.backend.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.notification.domain.model.Notification;

import java.util.UUID;

@Repository
public interface JpaNotificationRepository
        extends JpaRepository<Notification, UUID>,
        tn.steg.backend.notification.domain.repository.NotificationRepository {
}
