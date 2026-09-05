package tn.steg.backend.notification.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production gate: broken SMTP configuration fails fast at startup (when mail
 * is enabled) instead of silently dropping finance notifications.
 */
@DisplayName("SmtpConfigValidator tests (fail-fast mail config)")
class SmtpConfigValidatorTest {

    @Test
    @DisplayName("Valid configuration passes silently")
    void validPasses() {
        SmtpConfigValidator validator =
                new SmtpConfigValidator("smtp.steg.tn", 587, "no-reply@steg.tn", 5000, 10000, 10000);
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Blank host, bad port, bad sender and non-positive timeouts all fail fast")
    void invalidFailsFast() {
        assertThatThrownBy(() -> new SmtpConfigValidator("", 587, "no-reply@steg.tn", 5000, 10000, 10000).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("host");
        assertThatThrownBy(() -> new SmtpConfigValidator("smtp.steg.tn", 0, "no-reply@steg.tn", 5000, 10000, 10000).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> new SmtpConfigValidator("smtp.steg.tn", 587, "not-an-email", 5000, 10000, 10000).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sender");
        assertThatThrownBy(() -> new SmtpConfigValidator("smtp.steg.tn", 587, "no-reply@steg.tn", 0, 10000, 10000).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timeout");
    }
}
