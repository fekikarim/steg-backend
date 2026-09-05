package tn.steg.backend.notification.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.steg.backend.common.domain.event.ApplicationAcceptedEvent;
import tn.steg.backend.common.domain.event.ApplicationRejectedEvent;
import tn.steg.backend.common.domain.event.DocumentVerifiedEvent;
import tn.steg.backend.common.domain.event.InternshipAssignedEvent;
import tn.steg.backend.common.domain.event.JournalEntryValidatedEvent;
import tn.steg.backend.common.domain.event.NewPrivateMessageEvent;
import tn.steg.backend.common.domain.event.TaskAssignedEvent;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import java.util.List;

/**
 * Bridges business facts to notifications (Phase A10).
 *
 * <p>Each handler runs {@code BEFORE_COMMIT}, joining the publisher's own
 * transaction: the notification fan-out commits atomically with the business
 * fact (a rollback can never leave a phantom alert), uses no extra JDBC
 * connection (a separate commit-time transaction would double peak connection
 * demand and starve the pool under send bursts), and needs no async machinery.
 * Handlers depend only on {@code common} events — business modules never
 * depend on this listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {
        notificationService.dispatch(
                "Application accepted",
                "Your internship application " + event.applicationReference() + " has been accepted."
                        + " An internship will be created shortly.",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                List.of(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationRejected(ApplicationRejectedEvent event) {
        String reason = event.reason() != null && !event.reason().isBlank()
                ? " Reason: " + event.reason().strip()
                : "";
        notificationService.dispatch(
                "Application decision",
                "Your internship application " + event.applicationReference() + " has been rejected." + reason,
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                List.of(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onInternshipAssigned(InternshipAssignedEvent event) {
        notificationService.dispatch(
                "Internship assigned",
                "You have been assigned to " + event.departmentName()
                        + " for internship " + event.internshipReference()
                        + ". Your supervisor will contact you.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                List.of(event.internUserId()), event.actorId());
        notificationService.dispatch(
                "New intern assigned",
                "Internship " + event.internshipReference() + " has been assigned to "
                        + event.departmentName() + " under your supervision.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                List.of(event.supervisorUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDocumentVerified(DocumentVerifiedEvent event) {
        notificationService.dispatch(
                "Document " + event.documentType() + " " + event.verificationStatus().toLowerCase().replace('_', ' '),
                "Your document (" + event.documentType() + ") for application "
                        + event.applicationId() + " was marked: " + event.verificationStatus() + ".",
                NotificationPriority.NORMAL,
                "ApplicationDocument", event.documentId(),
                List.of(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTaskAssigned(TaskAssignedEvent event) {
        notificationService.dispatch(
                "New task assigned",
                "You have been assigned the task '" + event.taskTitle() + "'.",
                NotificationPriority.NORMAL,
                "Task", event.taskId(),
                List.of(event.assigneeUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onJournalEntryValidated(JournalEntryValidatedEvent event) {
        notificationService.dispatch(
                "Journal entry validated",
                "Your journal entry '" + event.entryTitle() + "' has been validated by your supervisor.",
                NotificationPriority.NORMAL,
                "JournalEntry", event.entryId(),
                List.of(event.internUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onNewPrivateMessage(NewPrivateMessageEvent event) {
        if (event.recipientIds().isEmpty()) {
            return;
        }
        String kind = "GROUP".equalsIgnoreCase(event.conversationType()) ? "group message" : "private message";
        notificationService.dispatch(
                "New message",
                "New " + kind + " from " + event.senderEmail() + ".",
                NotificationPriority.LOW,
                "Conversation", event.conversationId(),
                event.recipientIds(), event.actorId());
    }
}
