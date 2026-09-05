package tn.steg.backend.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private NotificationDeliveryStatus status = NotificationDeliveryStatus.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /**
     * Bounded retry bookkeeping (Phase A10): incremented on every failed
     * attempt; the sweep stops retrying at {@code maxAttempts} (the row stays
     * FAILED — visible, never silently dropped).
     */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    public NotificationDelivery(Notification notification, User recipient, NotificationChannel channel) {
        this.notification = notification;
        this.recipient = recipient;
        this.channel = channel;
        this.status = NotificationDeliveryStatus.PENDING;
    }
}
