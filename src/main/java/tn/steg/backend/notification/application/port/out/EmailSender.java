package tn.steg.backend.notification.application.port.out;

/**
 * Outbound port for transactional email (Phase A10).
 * Production adapter is Resend REST API (backend-only, API key never leaves server);
 * no SMTP is involved. Implementations signal failure by throwing (unchecked);
 * the service records the reason and retries later.
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

    /**
     * Sends with a provider-level idempotency key. Retries of the same logical
     * email (e.g. after a timeout where the original may still have gone
     * through) MUST reuse the same key so the provider delivers it once.
     * Default implementation ignores the key for adapters without support.
     *
     * @param idempotencyKey stable key per logical email; may be null
     * @throws RuntimeException on any delivery failure
     */
    default void send(String to, String subject, String body, String idempotencyKey) {
        send(to, subject, body);
    }
}
