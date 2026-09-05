package tn.steg.backend.notification.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.notification.domain.model.NotificationChannel;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A10: pure channel-selection rule — IN_APP always; EMAIL for
 * HIGH/URGENT when SMTP is enabled and the user opted in; PUSH for URGENT.
 */
@DisplayName("Notification Channel Selection Tests (A10)")
class NotificationChannelSelectionTest {

    @Test
    @DisplayName("LOW/NORMAL select IN_APP only, regardless of mail flags")
    void lowAndNormalInAppOnly() {
        assertThat(NotificationService.resolveChannels(NotificationPriority.LOW, true, true))
                .containsExactly(NotificationChannel.IN_APP);
        assertThat(NotificationService.resolveChannels(NotificationPriority.NORMAL, true, true))
                .containsExactly(NotificationChannel.IN_APP);
        assertThat(NotificationService.resolveChannels(NotificationPriority.NORMAL, false, false))
                .containsExactly(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("HIGH adds EMAIL only when SMTP enabled and user opted in")
    void highAddsEmailConditionally() {
        assertThat(NotificationService.resolveChannels(NotificationPriority.HIGH, true, true))
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
        assertThat(NotificationService.resolveChannels(NotificationPriority.HIGH, false, true))
                .containsExactly(NotificationChannel.IN_APP);
        assertThat(NotificationService.resolveChannels(NotificationPriority.HIGH, true, false))
                .containsExactly(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("URGENT fans out to all three channels when mail is on")
    void urgentFansOut() {
        assertThat(NotificationService.resolveChannels(NotificationPriority.URGENT, true, true))
                .containsExactlyInAnyOrder(
                        NotificationChannel.IN_APP, NotificationChannel.EMAIL, NotificationChannel.PUSH);
        assertThat(NotificationService.resolveChannels(NotificationPriority.URGENT, false, true))
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.PUSH);
    }
}
