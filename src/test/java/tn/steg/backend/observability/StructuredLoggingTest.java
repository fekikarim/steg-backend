package tn.steg.backend.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.common.infrastructure.logging.TraceIdFilter;
import tn.steg.backend.iam.application.dto.RegisterRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A13 – structured JSON logging and end-to-end trace correlation.
 *
 * Verifies three things:
 *  1. {@link TraceIdFilter} propagates the X-Trace-Id header through the MDC,
 *     into every JSON log line (via the Logstash encoder) and back into the
 *     error envelope — the A0 error-envelope traceId contract is preserved.
 *  2. Rejected validation values (potential secrets) are redacted from logs
 *     while still being echoed to the caller in the envelope.
 *  3. The active console encoder is the structured JSON Logstash encoder, so
 *     the "one JSON object per event" guarantee cannot silently regress.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Structured logging & correlation")
class StructuredLoggingTest {

    private static final String GLOBAL_HANDLER_LOGGER =
            "tn.steg.backend.common.interfaces.rest.GlobalExceptionHandler";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private final SnapshottingListAppender appender = new SnapshottingListAppender();

    @AfterEach
    void detachLogCapture() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        lc.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("X-Trace-Id propagates through MDC, JSON log lines and the error envelope")
    void traceIdPropagatesThroughMdcJsonAndEnvelope() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        attachLogCapture();

        String traceId = "it-3f9c1a77-2bb1-46e8-a19d-8ec81547b7a1";
        RegisterRequest invalid = RegisterRequest.builder()
                .email("not-an-email")
                .password("ValidPass#123")
                .firstName("Test")
                .lastName("User")
                .phone("+216 71 123 456")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Trace-Id"))
                        .isEqualTo(traceId));

        List<ILoggingEvent> validationEvents = appender.list.stream()
                .filter(e -> GLOBAL_HANDLER_LOGGER.equals(e.getLoggerName()))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("Validation error"))
                .toList();
        assertThat(validationEvents).isNotEmpty();
        ILoggingEvent event = validationEvents.get(0);
        event.prepareForDeferredProcessing();
        assertThat(event.getMDCPropertyMap())
                .containsEntry(TraceIdFilter.MDC_TRACE_KEY, traceId);
        assertThat(appender.mdcSnapshots)
                .anySatisfy(snapshot -> assertThat(snapshot)
                        .containsEntry(TraceIdFilter.MDC_TRACE_KEY, traceId));

        String json = encodeJson(event);
        assertThat(json)
                .contains("\"traceId\":\"" + traceId + "\"")
                .contains("\"@timestamp\"")
                .contains("\"message\"");
    }

    @Test
    @DisplayName("rejected validation values are redacted from logs but echoed in the envelope")
    void secretsNeverAppearInLogs() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        attachLogCapture();

        String secret = "A13*DoNotLog*83821";
        RegisterRequest invalid = RegisterRequest.builder()
                .email(secret)
                .password("ValidPass#123")
                .firstName("Test")
                .lastName("User")
                .phone("+216 71 123 456")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "it-secret-redaction-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").value(secret));

        for (ILoggingEvent event : appender.list) {
            event.prepareForDeferredProcessing();
            assertThat(event.getFormattedMessage())
                    .as("raw event message must never contain the secret")
                    .doesNotContain(secret);
            assertThat(encodeJson(event))
                    .as("JSON-encoded event must never contain the secret")
                    .doesNotContain(secret);
        }

        ILoggingEvent warnEvent = appender.list.stream()
                .filter(e -> GLOBAL_HANDLER_LOGGER.equals(e.getLoggerName()))
                .filter(e -> e.getLevel().equals(Level.WARN))
                .findFirst()
                .orElseThrow();
        warnEvent.prepareForDeferredProcessing();
        String warnJson = encodeJson(warnEvent);
        assertThat(warnJson).contains("[REDACTED]").doesNotContain(secret);
    }

    @Test
    @DisplayName("the active console encoder is the structured JSON Logstash encoder")
    void consoleAppenderUsesStructuredJsonEncoder() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.core.Appender<ILoggingEvent> console = lc
                .getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertThat(console).isInstanceOf(ConsoleAppender.class);
        assertThat(((ConsoleAppender<?>) console).getEncoder())
                .isInstanceOf(LogstashEncoder.class);
    }

    @Test
    @DisplayName("TraceIdFilter is registered in the application context (auto-deployed at runtime)")
    void traceIdFilterIsRegistered() {
        assertThat(context.getBean(TraceIdFilter.class)).isNotNull();
    }

    private MockMvc buildMockMvc() {
        // MockMvc does not apply servlet-context (@Component) filter beans when
        // springSecurity() configures the chain, so the tracing filter is added
        // explicitly. In the running application, Spring Boot auto-registers it
        // BEFORE the security filter chain (see traceIdFilterIsRegistered).
        return MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(new TraceIdFilter())
                .apply(springSecurity())
                .build();
    }

    private void attachLogCapture() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender.setContext(lc);
        appender.start();
        lc.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    private String encodeJson(ILoggingEvent event) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.core.Appender<ILoggingEvent> console = lc
                .getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        LogstashEncoder encoder = (LogstashEncoder)
                ((ch.qos.logback.core.ConsoleAppender<?>) console).getEncoder();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    /**
     * Captures events while the service thread still holds the request MDC
     * context. Logback snapshots MDC only at {@code prepareForDeferredProcessing}
     * time; doing it here (instead of later in the test) keeps the traceId
     * available for post-flight assertions even after the filter has cleared it.
     */
    private static final class SnapshottingListAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> list = new ArrayList<>();
        final List<Map<String, String>> mdcSnapshots = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            mdcSnapshots.add(snapshot == null ? Map.of() : snapshot);
            event.prepareForDeferredProcessing();
            list.add(event);
        }
    }
}