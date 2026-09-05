package tn.steg.backend.notification.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.notification.application.dto.NotificationPayload;
import tn.steg.backend.notification.application.dto.NotificationResponse;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.notification.application.port.out.PushNotificationSender;
import tn.steg.backend.notification.application.port.out.RealtimeNotifier;
import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationChannel;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationDeliveryStatus;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.notification.domain.repository.NotificationDeliveryRepository;
import tn.steg.backend.notification.domain.repository.NotificationRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application service for the Notification module (A10).
 *
 * <p>Fan-out model: one {@link Notification} per business fact, one
 * {@link NotificationDelivery} per recipient per channel. Channel selection:
 * IN_APP always; EMAIL for HIGH/URGENT priorities when SMTP is enabled and the
 * recipient opted in; PUSH for URGENT only (no-op stub until FCM/APNs lands).
 *
 * <p>No paid external notification SaaS is used: email goes through
 * {@code JavaMailSender} (configurable SMTP, e.g. a free/dev provider) and
 * in-app through the persisted delivery plus the Phase A9 STOMP broker.
 *
 * <p>Failures are recorded with a reason and retried with bounded exponential
 * backoff ({@code retryFailedDeliveries}, driven by a {@code @Scheduled} sweep);
 * rows that exhaust attempts stay FAILED — visible, never silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int RETRY_BATCH_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final PushNotificationSender pushSender;
    private final RealtimeNotifier realtimeNotifier;
    private final AuditService auditService;

    @Value("${steg.notifications.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${steg.notifications.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${steg.notifications.retry.base-backoff-seconds:60}")
    private long baseBackoffSeconds;

    @Value("${steg.notifications.retry.max-backoff-seconds:3600}")
    private long maxBackoffSeconds;


    // -------------------------------------------------------------------------
    // Fan-out
    // -------------------------------------------------------------------------

    /**
     * Persists a notification and immediately attempts every selected channel.
     * Unknown recipient ids are skipped (logged) so one stale id cannot fail
     * the whole fan-out.
     */
    @Transactional
    public Notification dispatch(String title, String message, NotificationPriority priority,
                                 String relatedEntityType, UUID relatedEntityId,
                                 Collection<UUID> recipientIds, UUID actorId) {
        Notification notification = new Notification(title, message, priority);
        notification.setRelatedEntityType(relatedEntityType);
        notification.setRelatedEntityId(relatedEntityId);
        notification = notificationRepository.save(notification);

        List<NotificationDelivery> deliveries = new ArrayList<>();
        for (UUID recipientId : new LinkedHashSet<>(recipientIds)) {
            User recipient = userRepository.findById(recipientId).orElse(null);
            if (recipient == null) {
                log.warn("Skipping notification {} for unknown recipient {}", notification.getId(), recipientId);
                continue;
            }
            for (NotificationChannel channel : resolveChannels(priority, mailEnabled,
                    Boolean.TRUE.equals(recipient.getEmailNotificationsEnabled()))) {
                NotificationDelivery delivery = new NotificationDelivery(notification, recipient, channel);
                attempt(delivery, recipient);
                deliveries.add(delivery);
            }
        }
        log.info("Notification dispatched: id={} priority={} recipients={} actor={}",
                notification.getId(), priority, deliveries.size(), actorId);
        for (NotificationDelivery delivery : deliveries) {
            deliveryRepository.save(delivery);
        }
        return notification;
    }

    /**
     * Pure channel-selection rule (unit-tested):
     * IN_APP always; EMAIL for HIGH/URGENT when SMTP is enabled and the user
     * opted in; PUSH for URGENT only.
     */
    static Set<NotificationChannel> resolveChannels(NotificationPriority priority,
                                                     boolean mailEnabled,
                                                     boolean emailOptIn) {
        Set<NotificationChannel> channels = EnumSet.of(NotificationChannel.IN_APP);
        if ((priority == NotificationPriority.HIGH || priority == NotificationPriority.URGENT)
                && mailEnabled && emailOptIn) {
            channels.add(NotificationChannel.EMAIL);
        }
        if (priority == NotificationPriority.URGENT) {
            channels.add(NotificationChannel.PUSH);
        }
        return channels;
    }

    private void attempt(NotificationDelivery delivery, User recipient) {
        try {
            switch (delivery.getChannel()) {
                case IN_APP -> {
                    delivery.setStatus(NotificationDeliveryStatus.SENT);
                    delivery.setSentAt(Instant.now());
                    delivery.setDeliveredAt(Instant.now());
                    pushBestEffort(delivery, recipient);
                }
                case EMAIL -> {
                    emailSender.send(recipient.getEmail(),
                            delivery.getNotification().getTitle(),
                            delivery.getNotification().getMessage());
                    delivery.setStatus(NotificationDeliveryStatus.SENT);
                    delivery.setSentAt(Instant.now());
                }
                case PUSH -> {
                    pushSender.send(recipient.getId(),
                            delivery.getNotification().getTitle(),
                            delivery.getNotification().getMessage());
                    delivery.setStatus(NotificationDeliveryStatus.SENT);
                    delivery.setSentAt(Instant.now());
                }
            }
        } catch (Exception e) {
            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setFailureReason(truncate(e.getMessage()));
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds(delivery.getAttemptCount())));
            log.warn("Notification delivery failed: notification={} channel={} recipient={} attempt={}: {}",
                    delivery.getNotification().getId(), delivery.getChannel(),
                    recipient.getId(), delivery.getAttemptCount(), e.getMessage());
            if (delivery.getAttemptCount() >= maxAttempts) {
                // Dead letter stays queryable AND auditable: operators can find
                // every exhausted delivery through both the table and the log.
                auditService.log("NOTIFICATION_DEAD_LETTER", "NotificationDelivery", delivery.getId(),
                        null,
                        java.util.Map.of("channel", delivery.getChannel().name(),
                                "recipient", recipient.getId().toString(),
                                "reason", String.valueOf(delivery.getFailureReason())),
                        null, null);
            }
        }
    }

    /**
     * Live WS push is transport nicety only: the persisted IN_APP row is the
     * authority, so push failures only log (never fail the delivery).
     */
    private void pushBestEffort(NotificationDelivery delivery, User recipient) {
        try {
            realtimeNotifier.pushToUser(recipient.getEmail(),
                    NotificationPayload.from(delivery.getNotification()));
        } catch (Exception e) {
            log.warn("Realtime notification push failed for recipient {}: {}",
                    recipient.getId(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Reads (strictly scoped to the caller's own deliveries)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listMine(UserPrincipal actor, boolean unreadOnly, Pageable pageable) {
        Page<NotificationDelivery> page = unreadOnly
                ? deliveryRepository.findUnreadInAppByRecipientId(actor.getId(), pageable)
                : deliveryRepository.findInAppByRecipientId(actor.getId(), pageable);
        return page.map(delivery -> NotificationResponse.from(delivery.getNotification(), delivery));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UserPrincipal actor) {
        return deliveryRepository.countUnreadInAppByRecipientId(actor.getId());
    }

    @Transactional
    public NotificationResponse markRead(UUID notificationId, UserPrincipal actor) {
        NotificationDelivery delivery = deliveryRepository
                .findInAppByNotificationIdAndRecipientId(notificationId, actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
        if (delivery.getReadAt() == null) {
            delivery.setReadAt(Instant.now());
            delivery.setStatus(NotificationDeliveryStatus.READ);
            deliveryRepository.save(delivery);
        }
        return NotificationResponse.from(delivery.getNotification(), delivery);
    }

    @Transactional
    public long markAllRead(UserPrincipal actor) {
        List<NotificationDelivery> updated = new ArrayList<>();
        Pageable page = PageRequest.of(0, RETRY_BATCH_SIZE);
        Page<NotificationDelivery> unread;
        do {
            unread = deliveryRepository.findUnreadInAppByRecipientId(actor.getId(), page);
            for (NotificationDelivery delivery : unread.getContent()) {
                delivery.setReadAt(Instant.now());
                delivery.setStatus(NotificationDeliveryStatus.READ);
                updated.add(delivery);
            }
            for (NotificationDelivery delivery : unread.getContent()) {
                deliveryRepository.save(delivery);
            }
        } while (unread.hasNext());
        return updated.size();
    }

    // -------------------------------------------------------------------------
    // Bounded retry (driven by the @Scheduled sweep; callable directly in tests)
    // -------------------------------------------------------------------------

    /**
     * Re-attempts FAILED deliveries whose backoff elapsed and which still have
     * attempts left. Each row is isolated: one poison row cannot abort the batch.
     * Rows past {@code maxAttempts} stay FAILED (dead-letter, queryable).
     *
     * @return number of deliveries that reached SENT in this sweep
     */
    @Transactional
    public int retryFailedDeliveries() {
        List<NotificationDelivery> batch = deliveryRepository.findRetryable(
                NotificationDeliveryStatus.FAILED, maxAttempts, Instant.now(),
                PageRequest.of(0, RETRY_BATCH_SIZE));
        int recovered = 0;
        for (NotificationDelivery delivery : batch) {
            try {
                attempt(delivery, delivery.getRecipient());
                deliveryRepository.save(delivery);
                if (delivery.getStatus() == NotificationDeliveryStatus.SENT) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Retry sweep failed to persist delivery {}: {}", delivery.getId(), e.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            log.info("Notification retry sweep: batch={} recovered={}", batch.size(), recovered);
        }
        return recovered;
    }

    long backoffSeconds(int attempt) {
        long backoff = baseBackoffSeconds * (1L << Math.min(attempt - 1, 10));
        return Math.min(backoff, maxBackoffSeconds);
    }

    private String truncate(String message) {
        if (message == null) {
            return "Unknown delivery failure";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
