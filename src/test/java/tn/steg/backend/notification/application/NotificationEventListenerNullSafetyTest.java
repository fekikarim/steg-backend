package tn.steg.backend.notification.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tn.steg.backend.common.domain.event.ApplicationSubmittedEvent;
import tn.steg.backend.common.domain.event.CertificateAvailableEvent;
import tn.steg.backend.common.domain.event.InternshipAssignedEvent;
import tn.steg.backend.common.domain.event.JournalEntryValidatedEvent;
import tn.steg.backend.common.domain.event.NewPrivateMessageEvent;
import tn.steg.backend.common.domain.event.PaymentApprovedEvent;
import tn.steg.backend.common.domain.event.TaskAssignedEvent;
import tn.steg.backend.common.domain.event.TaskDeletedEvent;
import tn.steg.backend.common.domain.event.TaskUpdatedEvent;
import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.notification.domain.model.NotificationType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Task 3 — notification event detail builders must never NPE on absent
 * real-world data. The listener bridges business events to fan-outs and used
 * {@code List.of(event.…UserId())}, which throws the moment an id is null
 * (no supervisor row on a legacy employee-backed assignment, an unassigned
 * task, a certificate for a staff-created profile without an account, an
 * application event without a linked user). Every such path now builds the
 * recipient list with {@code NullSafe.listOf} and notifies no one for the
 * missing id instead of failing the publisher's transaction.
 */
@DisplayName("NotificationEventListener — null recipient ids degrade to empty fan-outs, never NPE")
class NotificationEventListenerNullSafetyTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener = new NotificationEventListener(notificationService);

    @Test
    @DisplayName("no supervisor: the intern notification still goes out, the supervisor fan-out is empty")
    void noSupervisorDegradesToEmptySupervisorFanOut() {
        UUID internId = UUID.randomUUID();
        listener.onInternshipAssigned(new InternshipAssignedEvent(
                UUID.randomUUID(), "INT-NS-1", internId, null, "Tunis IT", UUID.randomUUID()));

        ArgumentCaptor<Collection<UUID>> internRecipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService, org.mockito.Mockito.times(2)).dispatch(anyString(), anyString(), any(), any(), any(), internRecipients.capture(), any());
        // First fan-out: the intern; second: the absent supervisor (empty list).
        assertThat(internRecipients.getAllValues().get(0)).containsExactly(internId);
        assertThat(internRecipients.getAllValues().get(1)).isEmpty();
    }

    @Test
    @DisplayName("no assignee: TaskAssignedEvent with a null assignee notifies no one and never throws")
    void noAssigneeNotifiesNoOneWithoutThrowing() {
        assertThatCode(() -> listener.onTaskAssigned(new TaskAssignedEvent(
                UUID.randomUUID(), "Rédiger le cahier", UUID.randomUUID(), null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).dispatch(eq(NotificationType.TASK_ASSIGNED),
                anyString(), anyString(), any(), any(), any(), recipients.capture(), any());
        assertThat(recipients.getValue()).isEmpty();
    }

    @Test
    @DisplayName("task edit/delete events with no resolved users fan out to nobody")
    void taskUpdatedAndDeletedWithoutUsersNotifyNoOne() {
        assertThatCode(() -> listener.onTaskUpdated(new TaskUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "Cahier", null, null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onTaskDeleted(new TaskDeletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "Cahier", null, null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("no supervisor on payment approval: fan-out is empty, the approval is never broken")
    void paymentApprovedWithoutSupervisorNotifiesNoOne() {
        assertThatCode(() -> listener.onPaymentApproved(new PaymentApprovedEvent(
                UUID.randomUUID(), "FC-2026-00001", null,
                new BigDecimal("150.00"), 3, UUID.randomUUID())))
                .doesNotThrowAnyException();
        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).dispatch(anyString(), anyString(), any(), any(), any(), recipients.capture(), any());
        assertThat(recipients.getValue()).isEmpty();
    }

    @Test
    @DisplayName("application event without a linked user: submission still reaches the Admin role fan-out")
    void applicationSubmittedWithoutCandidateUserStillAlertsAdmins() {
        assertThatCode(() -> listener.onApplicationSubmitted(new ApplicationSubmittedEvent(
                UUID.randomUUID(), "APP-NS-1", null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        // dispatchOnce for the candidate (empty recipients) + dispatchToRole for Admins.
        verify(notificationService).dispatchOnce(eq(NotificationType.APPLICATION_SUBMITTED),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(notificationService).dispatchToRole(eq(NotificationType.APPLICATION_SUBMITTED),
                anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("certificate for a staff-created profile without an account: no intern fan-out, no NPE")
    void certificateAvailableWithoutInternUserNotifiesNoOne() {
        assertThatCode(() -> listener.onCertificateAvailable(new CertificateAvailableEvent(
                UUID.randomUUID(), "CERT-2026-00009", UUID.randomUUID(), null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).dispatch(anyString(), anyString(), any(), any(), any(), recipients.capture(), any());
        assertThat(recipients.getValue()).isEmpty();
    }

    @Test
    @DisplayName("journal validation without an intern user: empty fan-out, never NPE")
    void journalValidatedWithoutInternUserNotifiesNoOne() {
        assertThatCode(() -> listener.onJournalEntryValidated(new JournalEntryValidatedEvent(
                UUID.randomUUID(), "Semaine 3", UUID.randomUUID(), null, UUID.randomUUID())))
                .doesNotThrowAnyException();
        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).dispatch(anyString(), anyString(), any(), any(), any(), recipients.capture(), any());
        assertThat(recipients.getValue()).isEmpty();
    }

    @Test
    @DisplayName("private message with no resolvable members: returns silently")
    void privateMessageWithoutRecipientsReturnsSilently() {
        assertThatCode(() -> listener.onNewPrivateMessage(new NewPrivateMessageEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "sup@steg.tn",
                List.of(), "PRIVATE")))
                .doesNotThrowAnyException();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("the dedupe path still passes NotificationPriority values through untouched")
    void prioritiesArePassedThrough() {
        listener.onPaymentApproved(new PaymentApprovedEvent(
                UUID.randomUUID(), "FC-2026-00002", UUID.randomUUID(),
                new BigDecimal("150.00"), 3, UUID.randomUUID()));
        verify(notificationService).dispatch(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(NotificationPriority.HIGH), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
    }
}
