package tn.steg.backend.notification.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast Resend configuration validation — when mail is enabled, a missing
 * or malformed API key / sender must fail startup loudly, not silently drop
 * confirmation emails.
 */
@DisplayName("ResendConfigValidator tests (fail-fast mail config)")
class ResendConfigValidatorTest {

    @Test
    @DisplayName("Valid configuration passes silently")
    void validPasses() {
        ResendConfigValidator validator = new ResendConfigValidator("re_1234567890abcdef", "no-reply@steg.tn");
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Blank API key, blank sender and malformed sender all fail fast")
    void invalidFailsFast() {
        assertThatThrownBy(() -> new ResendConfigValidator("", "no-reply@steg.tn").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendConfigValidator("   ", "no-reply@steg.tn").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendConfigValidator("re_123", "not-an-email").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sender");
        assertThatThrownBy(() -> new ResendConfigValidator("re_123", "").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sender");
    }

    @Test
    @DisplayName("Non re_ prefix still passes but warns (shape check is lenient)")
    void nonRePrefixWarnsButPasses() {
        // Resend keys are re_... but we only warn, not fail, for forward compatibility
        ResendConfigValidator validator = new ResendConfigValidator("test-key-not-re-prefix", "no-reply@steg.tn");
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }
}
