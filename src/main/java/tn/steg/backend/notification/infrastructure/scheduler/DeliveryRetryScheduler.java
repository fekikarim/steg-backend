package tn.steg.backend.notification.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tn.steg.backend.notification.application.NotificationService;

/**
 * Simple {@code @Scheduled} retry sweep (MVP-acceptable per spec): picks up
 * FAILED deliveries whose backoff elapsed and re-attempts them in bounded
 * batches. Never silently drops — rows past max attempts stay FAILED and
 * queryable (dead-letter).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRetryScheduler {

    private final NotificationService notificationService;

    @Scheduled(fixedDelayString = "${steg.notifications.retry.fixed-delay-millis:60000}")
    public void sweep() {
        try {
            notificationService.retryFailedDeliveries();
        } catch (Exception e) {
            log.error("Notification retry sweep failed: {}", e.getMessage());
        }
    }
}
