package tn.steg.backend.common.infrastructure.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.steg.backend.common.domain.event.ApplicationAcceptedEvent;
import tn.steg.backend.common.domain.event.ApplicationRejectedEvent;
import tn.steg.backend.common.domain.event.ApplicationSubmittedEvent;
import tn.steg.backend.common.domain.event.CertificateAvailableEvent;
import tn.steg.backend.common.domain.event.DocumentVerifiedEvent;
import tn.steg.backend.common.domain.event.InternshipAssignedEvent;
import tn.steg.backend.common.domain.event.PaymentApprovedEvent;

import java.util.Map;

/**
 * Production-ready back-office real-time broadcaster.
 * Listens to domain events (already published for notifications/audit) and
 * fans them out to dedicated STOMP topics that the Back Office subscribes to.
 * This gives each page a precise, low-noise trigger to refetch, without
 * polling or manual refresh.
 *
 * Topics:
 *  - /topic/backoffice/applications
 *  - /topic/backoffice/internships
 *  - /topic/backoffice/finance
 *  - /topic/backoffice/candidates
 *  - /topic/backoffice/departments
 *  - /topic/backoffice/employees
 *  - /topic/backoffice/audit
 *  - /topic/backoffice/dashboard (aggregate for KPIs)
 *
 * Notifications remain on /user/queue/notifications for personal alerts;
 * this broadcaster is for shared entity state. Both are best-effort.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackofficeRealtimeBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        // E2 step 11: the new application appears in the Back Office with no refresh.
        broadcast("applications", Map.of(
                "action", "submitted",
                "entityType", "InternshipApplication",
                "entityId", event.applicationId().toString(),
                "reference", event.applicationReference()
        ));
        broadcastDashboard();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {        broadcast("applications", Map.of(
                "action", "accepted",
                "entityType", "InternshipApplication",
                "entityId", event.applicationId().toString(),
                "reference", event.applicationReference()
        ));
        broadcastDashboard();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationRejected(ApplicationRejectedEvent event) {
        broadcast("applications", Map.of(
                "action", "rejected",
                "entityType", "InternshipApplication",
                "entityId", event.applicationId().toString(),
                "reference", event.applicationReference()
        ));
        broadcastDashboard();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInternshipAssigned(InternshipAssignedEvent event) {
        broadcast("internships", Map.of(
                "action", "assigned",
                "entityType", "Internship",
                "entityId", event.internshipId().toString(),
                "reference", event.internshipReference(),
                "department", event.departmentName()
        ));
        broadcast("candidates", Map.of(
                "action", "assigned",
                "entityType", "Internship",
                "entityId", event.internshipId().toString()
        ));
        broadcastDashboard();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentVerified(DocumentVerifiedEvent event) {
        broadcast("applications", Map.of(
                "action", "document_verified",
                "entityType", "ApplicationDocument",
                "entityId", event.documentId().toString(),
                "status", event.verificationStatus()
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCertificateAvailable(CertificateAvailableEvent event) {
        broadcast("internships", Map.of(
                "action", "certificate_available",
                "entityType", "Certificate",
                "entityId", event.certificateId().toString(),
                "internshipId", event.internshipId().toString()
        ));
        broadcastDashboard();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        broadcast("finance", Map.of(
                "action", "approved",
                "entityType", "FinanceCase",
                "entityId", event.financeCaseId().toString()
        ));
        broadcastDashboard();
    }

    // Generic fallback for any audit-logged mutation: the AuditService could
    // publish an AuditCreatedEvent, but until then we expose a manual hook.
    public void broadcastAudit(String action, String entityType, String entityId) {
        broadcast("audit", Map.of(
                "action", action,
                "entityType", entityType,
                "entityId", entityId
        ));
    }

    private void broadcast(String topicSuffix, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend("/topic/backoffice/" + topicSuffix, (Object) payload);
            // Also fan out to dashboard aggregate so KPIs stay fresh
            if (!"dashboard".equals(topicSuffix)) {
                messagingTemplate.convertAndSend("/topic/backoffice/dashboard", (Object) payload);
            }
        } catch (Exception e) {
            log.warn("Back-office broadcast failed for {}: {}", topicSuffix, e.getMessage());
        }
    }

    private void broadcastDashboard() {
        try {
            messagingTemplate.convertAndSend("/topic/backoffice/dashboard", (Object) Map.of("action", "refresh"));
        } catch (Exception e) {
            log.warn("Dashboard broadcast failed: {}", e.getMessage());
        }
    }
}
