package tn.steg.backend.notification.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fail-fast Resend configuration validation. Active only when
 * {@code steg.notifications.mail.enabled=true}.
 *
 * <p>Checks presence/shape of API key and sender address WITHOUT ever reading
 * or logging the key value. A broken mail configuration fails startup loudly
 * instead of silently dropping confirmation emails in production.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "steg.notifications.mail", name = "enabled", havingValue = "true")
public class ResendConfigValidator implements ApplicationRunner {

    private final String apiKey;
    private final String from;

    public ResendConfigValidator(
            @Value("${steg.notifications.resend.api-key:}") String apiKey,
            @Value("${steg.notifications.mail.from:}") String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Resend misconfigured: RESEND_API_KEY is blank while notifications.mail.enabled=true. "
                    + "Set RESEND_API_KEY in backend .env (never commit it).");
        }
        // Resend keys are re_<alphanumeric> — basic shape check, not a secret read
        if (!apiKey.startsWith("re_")) {
            log.warn("Resend API key does not start with re_ — check RESEND_API_KEY shape (value not logged).");
        }
        if (from == null || !from.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalStateException(
                    "Resend misconfigured: steg.notifications.mail.from must be a valid sender address.");
        }
        // Never log the key — only that validation passed
        log.info("Resend configuration validated (from={}, keyPresent=true). Email delivery enabled.", from);
    }
}
