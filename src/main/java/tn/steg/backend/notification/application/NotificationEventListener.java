package tn.steg.backend.notification.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.steg.backend.common.domain.event.ApplicationAcceptedEvent;
import tn.steg.backend.common.domain.event.ApplicationModificationRequestedEvent;
import tn.steg.backend.common.domain.event.ApplicationRejectedEvent;
import tn.steg.backend.common.domain.event.ApplicationResubmittedEvent;
import tn.steg.backend.common.domain.event.ApplicationSubmittedEvent;
import tn.steg.backend.common.domain.event.DocumentRejectedEvent;
import tn.steg.backend.common.domain.event.DocumentVerifiedEvent;
import tn.steg.backend.common.domain.event.FinalEvaluationRequiredEvent;
import tn.steg.backend.common.domain.event.InternshipAssignedEvent;
import tn.steg.backend.common.domain.event.InternshipReportSubmittedEvent;
import tn.steg.backend.common.domain.event.InternshipStatusChangedEvent;
import tn.steg.backend.common.domain.event.JournalEntryValidatedEvent;
import tn.steg.backend.common.domain.event.CertificateAvailableEvent;
import tn.steg.backend.common.domain.event.CandidateValidatedEvent;
import tn.steg.backend.common.domain.event.NewPrivateMessageEvent;
import tn.steg.backend.common.domain.event.PaymentApprovedEvent;
import tn.steg.backend.common.domain.event.TaskAssignedEvent;
import tn.steg.backend.common.domain.event.TaskStatusChangedEvent;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import java.util.List;
import java.util.UUID;

import static tn.steg.backend.common.domain.util.NullSafe.listOf;


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
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        // Submission confirmation — professional French, STEG-branded, status + next steps, no sensitive data.
        // HIGH priority → IN_APP always + EMAIL when steg.notifications.mail.enabled + opt-in.
        // Email body is wrapped by MailTemplateService.generic (STEG layout) in NotificationService.
        // Exactly-once: keyed by application — retries, refreshes, duplicate
        // clicks, timeout recovery, or event reprocessing reuse the existing
        // notification instead of sending a second confirmation email.
        notificationService.dispatchOnce(
                "APP_SUBMITTED:" + event.applicationId(),
                "Candidature soumise — En attente de validation",
                "Votre candidature " + event.applicationReference()
                        + " a bien été soumise avec succès et est maintenant en attente de validation par le superviseur responsable.\n\n"
                        + "Statut actuel : En attente de validation\n"
                        + "Référence à conserver : " + event.applicationReference() + "\n\n"
                        + "Prochaines étapes :\n"
                        + "- Votre dossier sera examiné par le superviseur du département concerné\n"
                        + "- Vous recevrez une notification par e-mail et dans votre espace candidat dès qu'une décision sera prise\n"
                        + "- Vous pouvez suivre l'avancement à tout moment depuis votre espace candidat",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                listOf(event.candidateUserId()), event.actorId());
        // AGENTS.md §8.1: a new application request must reach every Admin.
        notificationService.dispatchToRole(
                "APP_SUBMITTED_ADMIN:" + event.applicationId(),
                NotificationService.ADMIN_ROLE_CODE,
                "Nouvelle candidature",
                "La candidature " + event.applicationReference()
                        + " vient d'être soumise et attend une décision.",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCandidateValidated(CandidateValidatedEvent event) {
        // AGENTS.md §8.1: a candidate validated their front-office account —
        // Admin ONLY (never supervisors). Exactly-once per candidate: a
        // repeated validation reuses the existing notification (dedupe key),
        // so no second alert and no second email. The related entity
        // (Candidate + id) is the deep link to the candidate dossier.
        String name = event.candidateName() != null && !event.candidateName().isBlank()
                ? event.candidateName().strip()
                : event.candidateEmail();
        notificationService.dispatchToRole(
                "CANDIDATE_VALIDATED:" + event.candidateId(),
                NotificationService.ADMIN_ROLE_CODE,
                "Nouveau candidat validé",
                "Le candidat " + name + " (" + event.candidateEmail() + ")"
                        + " vient de valider son compte sur le front-office."
                        + " Son dossier est disponible dans la gestion des candidats.",
                NotificationPriority.HIGH,
                "Candidate", event.candidateId(),
                event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationResubmitted(ApplicationResubmittedEvent event) {
        // A resubmission is a fresh review request: every Admin is alerted so
        // the corrected dossier is picked up again (AGENTS.md §4/§8.1).
        notificationService.dispatchToRole(
                "APP_RESUBMITTED:" + event.eventId(),
                NotificationService.ADMIN_ROLE_CODE,
                "Candidature resoumise",
                "La candidature " + event.applicationReference()
                        + " a été corrigée et resoumise. Une nouvelle décision est attendue.",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {
        notificationService.dispatch(
                "Application accepted",
                "Your internship application " + event.applicationReference() + " has been accepted."
                        + " An internship will be created shortly.",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                listOf(event.candidateUserId()), event.actorId());
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
                listOf(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationModificationRequested(ApplicationModificationRequestedEvent event) {
        String message = event.message() != null && !event.message().isBlank()
                ? " Message : " + event.message().strip()
                : "";
        notificationService.dispatch(
                "Modification demandée",
                "Votre candidature " + event.applicationReference()
                        + " nécessite des modifications." + message
                        + " Corrigez votre dossier puis resoumettez-le.",
                NotificationPriority.HIGH,
                "InternshipApplication", event.applicationId(),
                listOf(event.candidateUserId()), event.actorId());
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
                listOf(event.internUserId()), event.actorId());
        notificationService.dispatch(
                "New intern assigned",
                "Internship " + event.internshipReference() + " has been assigned to "
                        + event.departmentName() + " under your supervision.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                listOf(event.supervisorUserId()), event.actorId());
    }

    /**
     * Internship advances along the explicit §4 status model: the intern and
     * the current supervisor (Admin in Scenario 1) are informed. Recipients
     * are resolved by {@code InternshipLifecycleService} through
     * {@code SupervisionScopeService} at transition time, so a reassignment is
     * always reflected; a replayed event is deduplicated by its own id.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onInternshipStatusChanged(InternshipStatusChangedEvent event) {
        List<UUID> recipients = java.util.stream.Stream.of(
                        event.supervisorUserId(), event.internUserId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        notificationService.dispatchOnce(
                "INTERNSHIP_STATUS:" + event.eventId(),
                "Internship status changed",
                "Internship " + event.internshipReference() + " is now " + event.newStatus()
                        + (event.comment() != null && !event.comment().isBlank()
                                ? ". " + event.comment().strip()
                                : "."),
                NotificationPriority.NORMAL,
                "Internship", event.internshipId(), recipients, event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onInternshipReportSubmitted(InternshipReportSubmittedEvent event) {
        // S7a.1: the Admin owns the validation queue (§5.11) — fan out like a
        // new application request. The supervisor and the intern are already
        // notified by the lifecycle's own status-changed event.
        notificationService.dispatchToRole(
                "REPORT_SUBMITTED_ADMIN:" + event.internshipId(),
                NotificationService.ADMIN_ROLE_CODE,
                "Rapport de stage envoyé",
                "Le stagiaire de " + event.internshipReference()
                        + " a envoyé son rapport : le dossier attend la validation.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDocumentRejected(DocumentRejectedEvent event) {
        // S8 pre-check (d): the candidate learns WHAT to fix — comment included.
        notificationService.dispatchOnce(
                "DOCUMENT_REJECTED:" + event.internshipId() + ":" + event.documentType(),
                "Document refused — action required",
                "Your " + event.documentType().toLowerCase().replace('_', ' ')
                        + " for internship " + event.internshipReference()
                        + " was refused by the administration.\n\nReason: " + event.comment()
                        + "\n\nPlease submit a corrected version.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                listOf(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDocumentVerified(DocumentVerifiedEvent event) {
        notificationService.dispatch(
                "Document " + event.documentType() + " " + event.verificationStatus().toLowerCase().replace('_', ' '),
                "Your document (" + event.documentType() + ") for application "
                        + event.applicationId() + " was marked: " + event.verificationStatus() + ".",
                NotificationPriority.NORMAL,
                "ApplicationDocument", event.documentId(),
                listOf(event.candidateUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTaskAssigned(TaskAssignedEvent event) {
        notificationService.dispatch(
                "New task assigned",
                "You have been assigned the task '" + event.taskTitle() + "'.",
                NotificationPriority.NORMAL,
                "Task", event.taskId(),
                listOf(event.assigneeUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        List<UUID> recipients = java.util.stream.Stream.of(
                        event.supervisorUserId(), event.internUserId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        notificationService.dispatch(
                "Task status changed",
                "Task '" + event.taskTitle() + "' is now " + event.status() + ".",
                NotificationPriority.NORMAL,
                "Task", event.taskId(), recipients, event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onJournalEntryValidated(JournalEntryValidatedEvent event) {
        notificationService.dispatch(
                "Journal entry validated",
                "Your journal entry '" + event.entryTitle() + "' has been validated by your supervisor.",
                NotificationPriority.NORMAL,
                "JournalEntry", event.entryId(),
                listOf(event.internUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        notificationService.dispatch(
                "Payment approved",
                "The payment of " + event.amount() + " TND (" + event.paidMonths()
                        + " month(s)) for finance case " + event.financeCaseReference()
                        + " has been approved. The receipt is available.",
                NotificationPriority.HIGH,
                "FinanceCase", event.financeCaseId(),
                listOf(event.supervisorUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCertificateAvailable(CertificateAvailableEvent event) {
        notificationService.dispatch(
                "Certificate available",
                "Your internship certificate " + event.certificateReference() + " is available for download.",
                NotificationPriority.HIGH,
                "Certificate", event.certificateId(),
                listOf(event.internUserId()), event.actorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onNewPrivateMessage(NewPrivateMessageEvent event) {
        // Null-safe: a null recipient set (no resolvable members) notifies no
        // one instead of NPE-ing the messaging transaction.
        if (event.recipientIds() == null || event.recipientIds().isEmpty()) {
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

    /**
     * An internship ended without its FINAL report: administrative validation is
     * blocked, so every administrator is alerted (IN_APP + live push) and the
     * Back Office supervision table refreshes itself.
     *
     * <p>Exactly-once per internship: the dedupe key is shared with the
     * self-healing sweep, so a replayed event, a retry or a scheduled re-check
     * never produces a second alert.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onFinalEvaluationRequired(FinalEvaluationRequiredEvent event) {
        String candidate = event.candidateName() == null || event.candidateName().isBlank()
                ? ""
                : " (" + event.candidateName().strip() + ")";
        notificationService.dispatchToRole(
                NotificationService.FINAL_EVALUATION_DEDUPE_PREFIX + event.internshipId(),
                NotificationService.ADMIN_ROLE_CODE,
                NotificationService.FINAL_EVALUATION_TITLE,
                "Internship " + event.internshipReference() + candidate
                        + " reached COMPLETED without a final evaluation. "
                        + "Record the FINAL report to unblock administrative validation.",
                NotificationPriority.HIGH,
                "Internship", event.internshipId(),
                event.actorId());
    }
}
