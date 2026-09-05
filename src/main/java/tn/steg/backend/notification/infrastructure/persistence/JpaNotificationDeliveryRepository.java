package tn.steg.backend.notification.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationDeliveryStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaNotificationDeliveryRepository
        extends JpaRepository<NotificationDelivery, UUID>,
        tn.steg.backend.notification.domain.repository.NotificationDeliveryRepository {

    // NOTE: enum comparison uses the fully-qualified literal — a plain string
    // literal ('IN_APP') does not match enums mapped with EnumType.STRING in HQL.
    @Query("select d from NotificationDelivery d where d.recipient.id = :recipientId "
            + "and d.channel = tn.steg.backend.notification.domain.model.NotificationChannel.IN_APP "
            + "order by d.createdAt desc")
    Page<NotificationDelivery> findInAppByRecipientId(@Param("recipientId") UUID recipientId, Pageable pageable);

    @Query("select d from NotificationDelivery d where d.recipient.id = :recipientId "
            + "and d.channel = tn.steg.backend.notification.domain.model.NotificationChannel.IN_APP "
            + "and d.readAt is null order by d.createdAt desc")
    Page<NotificationDelivery> findUnreadInAppByRecipientId(@Param("recipientId") UUID recipientId, Pageable pageable);

    @Query("select d from NotificationDelivery d where d.notification.id = :notificationId "
            + "and d.recipient.id = :recipientId "
            + "and d.channel = tn.steg.backend.notification.domain.model.NotificationChannel.IN_APP")
    Optional<NotificationDelivery> findInAppByNotificationIdAndRecipientId(
            @Param("notificationId") UUID notificationId, @Param("recipientId") UUID recipientId);

    @Query("select count(d) from NotificationDelivery d where d.recipient.id = :recipientId "
            + "and d.channel = tn.steg.backend.notification.domain.model.NotificationChannel.IN_APP "
            + "and d.readAt is null")
    long countUnreadInAppByRecipientId(@Param("recipientId") UUID recipientId);

    List<NotificationDelivery> findByNotificationId(UUID notificationId);

    @Query("select d from NotificationDelivery d where d.status = :status "
            + "and d.attemptCount < :maxAttempts "
            + "and (d.nextRetryAt is null or d.nextRetryAt <= :now) order by d.nextRetryAt asc nulls first")
    List<NotificationDelivery> findRetryable(@Param("status") NotificationDeliveryStatus status,
                                             @Param("maxAttempts") int maxAttempts,
                                             @Param("now") Instant now,
                                             Pageable pageable);
}
