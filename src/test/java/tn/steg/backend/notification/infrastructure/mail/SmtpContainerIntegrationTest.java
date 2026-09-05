package tn.steg.backend.notification.infrastructure.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deployment-readiness gate (verification plan §8): sends a real email
 * through {@link SmtpEmailSender} over a genuine SMTP conversation to a
 * Testcontainers-managed MailHog instance, then confirms delivery via
 * MailHog's HTTP API — no mocked {@code JavaMailSender}, no stubbed
 * transport. This complements (does not replace) {@link SmtpConfigValidatorTest},
 * which only checks configuration shape, not actual delivery.
 */
@Testcontainers
@DisplayName("SMTP real-server container integration tests (deployment gate)")
class SmtpContainerIntegrationTest {

    @Container
    static final GenericContainer<?> MAILHOG = new GenericContainer<>(DockerImageName.parse("mailhog/mailhog"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v2/messages").forPort(8025).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private SmtpEmailSender senderPointedAtMailhog() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(MAILHOG.getHost());
        mailSender.setPort(MAILHOG.getMappedPort(1025));

        SmtpEmailSender sender = new SmtpEmailSender(mailSender);
        ReflectionTestUtils.setField(sender, "from", "no-reply@steg.tn");
        return sender;
    }

    /** Clears MailHog's inbox between tests so message lookups aren't polluted by earlier sends. */
    @BeforeEach
    void clearInbox() throws Exception {
        HttpRequest delete = HttpRequest.newBuilder(mailhogUri("/api/v1/messages")).DELETE().build();
        httpClient.send(delete, HttpResponse.BodyHandlers.discarding());
    }

    private URI mailhogUri(String path) {
        return URI.create("http://" + MAILHOG.getHost() + ":" + MAILHOG.getMappedPort(8025) + path);
    }

    private JsonNode fetchMessages() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(mailhogUri("/api/v2/messages")).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    @Test
    @DisplayName("A real email sent through SmtpEmailSender is actually delivered and readable via MailHog")
    void emailIsActuallyDelivered() throws Exception {
        String to = "intern-" + UUID.randomUUID() + "@example.com";
        String subject = "STEG certificate ready";
        String body = "Your internship certificate is ready for download.";

        senderPointedAtMailhog().send(to, subject, body);

        JsonNode messages = awaitAtLeastOneMessage();
        assertThat(messages.get("count").asInt()).isEqualTo(1);

        JsonNode item = messages.get("items").get(0);
        JsonNode headers = item.get("Content").get("Headers");
        assertThat(headers.get("From").get(0).asText()).isEqualTo("no-reply@steg.tn");
        assertThat(headers.get("To").get(0).asText()).isEqualTo(to);
        assertThat(headers.get("Subject").get(0).asText()).isEqualTo(subject);
        assertThat(item.get("Content").get("Body").asText()).isEqualTo(body);
    }

    private JsonNode awaitAtLeastOneMessage() throws Exception {
        JsonNode messages = fetchMessages();
        int attempts = 0;
        while (messages.get("count").asInt() < 1 && attempts++ < 20) {
            Thread.sleep(250);
            messages = fetchMessages();
        }
        return messages;
    }

    @Test
    @DisplayName("SmtpEmailSender never logs the SMTP password or the email body at INFO level")
    void doesNotLogSecretsOrBodyAtInfoLevel() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger senderLogger = loggerContext.getLogger(SmtpEmailSender.class);
        Level originalLevel = senderLogger.getLevel();
        senderLogger.setLevel(Level.INFO);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        senderLogger.addAppender(appender);

        String secretLookingPassword = "sw0rdfish-super-secret-smtp-password";
        String sensitiveBody = "CONFIDENTIAL body content that must not be logged.";
        try {
            senderPointedAtMailhog().send("audit@example.com", "Audit check", sensitiveBody);

            // The production code only ever calls log.debug(...) on success (see SmtpEmailSender),
            // so at INFO threshold, nothing should reach the appender at all.
            assertThat(appender.list).isEmpty();

            // Defensive check even if a future change adds INFO/WARN/ERROR logging: it must never
            // include the password or the raw body text.
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains(secretLookingPassword)
                            || event.getFormattedMessage().contains(sensitiveBody));
        } finally {
            senderLogger.detachAppender(appender);
            senderLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("A connection failure (unreachable SMTP server) surfaces as a clean, handled MailException")
    void connectionFailureSurfacesAsHandledException() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(freePort);
        mailSender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "1000");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.timeout", "1000");

        SmtpEmailSender sender = new SmtpEmailSender(mailSender);
        ReflectionTestUtils.setField(sender, "from", "no-reply@steg.tn");

        assertThatThrownBy(() -> sender.send("someone@example.com", "subject", "body"))
                .isInstanceOf(MailException.class);
    }
}
