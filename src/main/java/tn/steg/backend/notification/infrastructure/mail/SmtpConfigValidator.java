package tn.steg.backend.notification.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fail-fast SMTP configuration validation (Phase A11 hardening). Active only
 * when {@code steg.notifications.mail.enabled=true} (email stays disabled by
 * default in development).
 *
 * <p>Checks presence/shape of host, port, sender address and timeouts WITHOUT
 * ever reading or logging credentials (no password/token touch this class).
 * A broken mail configuration fails startup loudly instead of silently
 * dropping payment/case notifications in production.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "steg.notifications.mail", name = "enabled", havingValue = "true")
public class SmtpConfigValidator implements ApplicationRunner {

    private final String host;
    private final int port;
    private final String from;
    private final int connectionTimeoutMillis;
    private final int readTimeoutMillis;
    private final int writeTimeoutMillis;

    public SmtpConfigValidator(
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:25}") int port,
            @Value("${steg.notifications.mail.from:}") String from,
            @Value("${spring.mail.properties.mail.smtp.connectiontimeout:5000}") int connectionTimeoutMillis,
            @Value("${spring.mail.properties.mail.smtp.timeout:10000}") int readTimeoutMillis,
            @Value("${spring.mail.properties.mail.smtp.writetimeout:10000}") int writeTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.from = from;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "SMTP misconfigured: spring.mail.host is blank while notifications.mail.enabled=true.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(
                    "SMTP misconfigured: spring.mail.port must be 1-65535 while notifications.mail.enabled=true.");
        }
        if (from == null || !from.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalStateException(
                    "SMTP misconfigured: steg.notifications.mail.from must be a valid sender address.");
        }
        if (connectionTimeoutMillis <= 0 || readTimeoutMillis <= 0 || writeTimeoutMillis <= 0) {
            throw new IllegalStateException(
                    "SMTP misconfigured: connection/read/write timeouts must all be positive.");
        }
        // Host/port only — credentials are never read here and never logged anywhere.
        log.info("SMTP configuration validated (host={}, port={}, timeouts={}/{}/{}ms). Email delivery enabled.",
                host, port, connectionTimeoutMillis, readTimeoutMillis, writeTimeoutMillis);
    }
}
