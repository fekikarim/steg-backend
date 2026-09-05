package tn.steg.backend.notification.application.port.out;

/**
 * Outbound port for transactional email (Phase A10).
 * Production adapter wraps {@code JavaMailSender} against configurable SMTP;
 * no external notification SaaS is involved. Implementations signal failure
 * by throwing (unchecked); the service records the reason and retries later.
 */
public interface EmailSender {

    /**
     * Sends a plain-text email.
     *
     * @param to      recipient address (already validated upstream)
     * @param subject subject line
     * @param body    plain-text body
     * @throws RuntimeException on any delivery failure
     */
    void send(String to, String subject, String body);
}
