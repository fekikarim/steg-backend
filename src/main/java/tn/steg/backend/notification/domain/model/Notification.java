package tn.steg.backend.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 50)
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @Column(name = "related_entity_type", length = 100)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private UUID relatedEntityId;

    /**
     * Optional idempotency key (e.g. {@code APP_SUBMITTED:<applicationId>}).
     * Enforced unique by {@code uq_notifications_dedupe_key}; NULL means
     * "not deduped" (NULLs never collide in PostgreSQL). Guards exactly-once
     * confirmation emails across retries, refreshes, duplicate clicks,
     * timeout recovery, and event reprocessing.
     */
    @Column(name = "dedupe_key", length = 128, unique = true)
    private String dedupeKey;

    public Notification(String title, String message, NotificationPriority priority) {
        this.title = title;
        this.message = message;
        this.priority = priority;
    }
}
