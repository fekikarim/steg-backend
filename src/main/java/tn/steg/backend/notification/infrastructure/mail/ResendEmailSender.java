package tn.steg.backend.notification.infrastructure.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tn.steg.backend.notification.application.port.out.EmailSender;

import java.util.List;

/**
 * Production {@link EmailSender} over Resend REST API (https://api.resend.com/emails).
 *
 * <p>Backend-only — the API key is injected from {@code RESEND_API_KEY} env and never
 * reaches the frontend, mobile app, logs, or client bundles. All transactional emails
 * (application confirmation + future notifications) go through this path.
 */
@Slf4j
@Component
public class ResendEmailSender implements EmailSender {

    private final MailTemplateService templates;
    private final RestClient resendClient;
    private final String from;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResendEmailSender(
            MailTemplateService templates,
            @Value("${steg.notifications.resend.api-key:}") String apiKey,
            @Value("${steg.notifications.mail.from:no-reply@steg.tn}") String from,
            @Value("${steg.notifications.resend.timeout-millis:10000}") int timeoutMillis) {
        this.templates = templates;
        this.apiKey = apiKey != null ? apiKey : "";
        this.from = from;
        // Bounded latency is critical: this sender runs inside business transactions
        // (e.g. BEFORE_COMMIT on application submit). An unbounded call would hang
        // the HTTP request past the frontend timeout while the DB commit succeeds —
        // leaving the UI failed while the application is actually SUBMITTED.
        // Failures are caught by NotificationService.attempt() → FAILED + retry sweep.
        int readTimeout = Math.max(1000, timeoutMillis);
        int connectTimeout = Math.min(5000, readTimeout);
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(java.time.Duration.ofMillis(connectTimeout));
        rf.setReadTimeout(java.time.Duration.ofMillis(readTimeout));
        this.resendClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void send(String to, String subject, String body) {
        MailTemplateService.Mail mail = templates.generic(subject, body);
        sendInternal(to, mail);
    }

    /** Event-specific branded HTML mail (used by listeners for key workflow events). */
    public void sendTemplated(String to, MailTemplateService.Mail mail) {
        sendInternal(to, mail);
    }

    private record ResendPayload(String from, List<String> to, String subject, String html) {}

    private void sendInternal(String to, MailTemplateService.Mail mail) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Resend API key is blank — cannot send email to {}", maskRecipient(to));
            throw new EmailDeliveryException("Email delivery failed: Resend not configured", null);
        }
        try {
            String payload = objectMapper.writeValueAsString(new ResendPayload(from, List.of(to), mail.subject(), mail.html()));
            // Resend expects Authorization: Bearer re_... and Content-Type: application/json
            String response = resendClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            // Resend returns {"id":"..."} on success; log only id prefix, never body/to at info
            String id = "unknown";
            try {
                if (response != null) {
                    var node = objectMapper.readTree(response);
                    id = node.path("id").asText("unknown");
                }
            } catch (Exception ignored) {}
            log.debug("Resend email sent id={}", id);
        } catch (Exception ex) {
            // Never log the API key, body, or full recipient — only class + sanitized message
            String sanitized = sanitize(ex.getMessage());
            log.error("Failed to send Resend email to {}: {} - {}",
                    maskRecipient(to), ex.getClass().getSimpleName(), sanitized);
            throw new EmailDeliveryException("Email delivery failed", ex);
        }
    }

    private static String maskRecipient(String to) {
        if (to == null || !to.contains("@")) return "***";
        int at = to.indexOf('@');
        String local = to.substring(0, at);
        String domain = to.substring(at + 1);
        if (local.length() <= 2) return "***@" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + domain;
    }

    private static String sanitize(String msg) {
        if (msg == null) return "";
        // Truncate and strip any potential key leakage (ResendException may echo api key in rare cases)
        String s = msg.length() > 300 ? msg.substring(0, 300) : msg;
        return s.replaceAll("re_[A-Za-z0-9_-]{10,}", "re_***");
    }

    /**
     * Unchecked exception preserving the contract callers (and tests) rely on.
     * Wraps ResendException so that NotificationService's retry logic (FAILED → backoff)
     * still triggers, but without leaking SMTP-specific MailSendException.
     */
    public static class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
