package tn.steg.backend.notification.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.notification.application.port.out.PushNotificationSender;
import tn.steg.backend.notification.application.port.out.RealtimeNotifier;
import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.notification.domain.repository.NotificationDeliveryRepository;
import tn.steg.backend.notification.domain.repository.NotificationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Role-scoped fan-out: administrative alerts reach every active administrator
 * exactly once (dedupe key), disabled accounts are excluded, and an empty
 * staff set degrades to a no-op instead of failing the business transaction.
 */
@DisplayName("Notification role fan-out")
class NotificationRoleFanOutTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RealtimeNotifier realtime = mock(RealtimeNotifier.class);
    private final NotificationService service = new NotificationService(
            notifications, deliveries, users,
            mock(EmailSender.class), mock(PushNotificationSender.class), realtime, mock(AuditService.class),
            new tn.steg.backend.common.application.ApplicationTimeZone("Africa/Tunis"));

    private static User admin(String email, boolean enabled) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setEnabled(enabled);
        return user;
    }

    private void stubSave() {
        when(notifications.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("delivers and pushes live to every active role holder, skipping disabled accounts")
    void deliversToActiveRoleHolders() {
        User alice = admin("alice@steg.tn", true);
        User bob = admin("bob@steg.tn", true);
        User disabled = admin("off@steg.tn", false);
        when(users.findDistinctByAssignedRoles_Code("ADMIN")).thenReturn(List.of(alice, disabled, bob));
        Map<UUID, User> byId = Map.of(alice.getId(), alice, bob.getId(), bob, disabled.getId(), disabled);
        when(users.findById(any())).thenAnswer(inv -> Optional.ofNullable(byId.get(inv.<UUID>getArgument(0))));
        stubSave();

        service.dispatchToRole("FINAL_EVALUATION_REQUIRED:abc", "ADMIN", "Final evaluation required",
                "Internship STAGE-2026-000042 needs a FINAL report.", NotificationPriority.HIGH,
                "Internship", UUID.randomUUID(), null);

        ArgumentCaptor<NotificationDelivery> saved = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveries, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(d -> d.getRecipient().getEmail())
                .containsExactlyInAnyOrder("alice@steg.tn", "bob@steg.tn");
        verify(realtime, times(2)).pushToUser(anyString(), any());
        verify(realtime, never()).pushToUser(org.mockito.ArgumentMatchers.eq("off@steg.tn"), any());
    }

    @Test
    @DisplayName("no administrator is a logged no-op, never an exception")
    void emptyRoleIsANoOp() {
        when(users.findDistinctByAssignedRoles_Code("ADMIN")).thenReturn(List.of());
        stubSave();

        service.dispatchToRole(null, "ADMIN", "Final evaluation required", "message",
                NotificationPriority.HIGH, "Internship", UUID.randomUUID(), null);

        verify(notifications).save(any(Notification.class));
        verify(deliveries, never()).save(any(NotificationDelivery.class));
    }

    @Test
    @DisplayName("a dedupe hit short-circuits the whole fan-out")
    void dedupeHitSkipsFanOut() {
        Notification existing = new Notification("Final evaluation required", "message", NotificationPriority.HIGH);
        when(notifications.findByDedupeKey(anyString())).thenReturn(Optional.of(existing));

        Notification returned = service.dispatchToRole("FINAL_EVALUATION_REQUIRED:abc", "ADMIN",
                "Final evaluation required", "message", NotificationPriority.HIGH,
                "Internship", UUID.randomUUID(), null);

        assertThat(returned).isSameAs(existing);
        verify(notifications, never()).save(any(Notification.class));
        verify(realtime, never()).pushToUser(anyString(), any());
    }
}
