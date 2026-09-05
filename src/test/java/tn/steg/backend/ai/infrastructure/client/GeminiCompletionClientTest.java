package tn.steg.backend.ai.infrastructure.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A12 closure — Gemini client contract + logging-safety tests.
 *
 * <p>Uses a JDK-local stub HTTP server (no Spring context, no network, no real
 * credentials) to prove:
 * <ul>
 *   <li>the client speaks the {@code generateContent} contract (path, key query
 *       param, payload shape) and parses {@code candidates[0].content.parts[0].text};</li>
 *   <li>full prompts, response bodies, and the API key are NEVER written to the logs
 *       on success, malformed-response, or HTTP-error paths;</li>
 *   <li>disabled / missing-key / unresolved-placeholder configurations degrade
 *       gracefully without any network call.</li>
 * </ul>
 */
@DisplayName("GeminiCompletionClient — contract + logging safety")
class GeminiCompletionClientTest {

    private static final String FAKE_KEY = "FAKE-TEST-KEY-not-a-secret";
    private static final String PROMPT_MARKER = "PROMPT-SENSITIVE-MARKER-9f3k";
    private static final String RESPONSE_MARKER = "RESPONSE-SENSITIVE-MARKER-7q2x";

    private HttpServer stubServer;
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    private volatile byte[] nextResponseBody = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile int nextStatus = 200;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger clientLogger;

    @BeforeEach
    void setUp() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress(0), 0);
        stubServer.createContext("/", exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedQueries.add(exchange.getRequestURI().getRawQuery());
            exchange.getRequestBody().readAllBytes();
            byte[] body = nextResponseBody;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stubServer.setExecutor(Executors.newSingleThreadExecutor());
        stubServer.start();

        clientLogger = (Logger) LoggerFactory.getLogger(GeminiCompletionClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        stubServer.stop(0);
    }

    private AiProperties enabledProperties() {
        AiProperties props = new AiProperties();
        props.setEnabled(true);
        props.setApiKey(FAKE_KEY);
        props.setModel("test-model-flash");
        props.setBaseUrl("http://localhost:" + stubServer.getAddress().getPort());
        props.setTimeoutMs(5000);
        props.setMaxOutputTokens(512);
        props.setTemperature(0.2);
        return props;
    }

    private GeminiCompletionClient client(AiProperties props) {
        return new GeminiCompletionClient(props, new ObjectMapper());
    }

    private List<String> loggedMessages() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("success path speaks generateContent contract and never logs prompt/response/key")
    void successPathUsesContractAndLogsNothingSensitive() {
        String generated = "Bonjour — ceci est un texte genere avec " + RESPONSE_MARKER;
        nextResponseBody = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + "\"" + generated + "\"}]}}]}").getBytes(StandardCharsets.UTF_8);

        AiCompletionResult result = client(enabledProperties())
                .complete("Instruction syst\u00e8me " + PROMPT_MARKER, List.of("Question " + PROMPT_MARKER));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains(RESPONSE_MARKER);
        assertThat(result.modelName()).isEqualTo("test-model-flash");

        assertThat(receivedPaths).hasSize(1);
        assertThat(receivedPaths.get(0)).isEqualTo("/v1beta/models/test-model-flash:generateContent");
        assertThat(receivedQueries).hasSize(1);
        assertThat(receivedQueries.get(0)).contains("key=" + FAKE_KEY);

        assertThat(loggedMessages()).noneMatch(m ->
                m.contains(PROMPT_MARKER) || m.contains(RESPONSE_MARKER) || m.contains(FAKE_KEY));
    }

    @Test
    @DisplayName("malformed response degrades gracefully without logging the response body")
    void malformedResponseIsNotLogged() {
        nextResponseBody = ("{\"unexpected\":\"" + RESPONSE_MARKER + "\",\"candidates\":[]}").getBytes(StandardCharsets.UTF_8);

        AiCompletionResult result = client(enabledProperties())
                .complete("Instruction " + PROMPT_MARKER, List.of("Question " + PROMPT_MARKER));

        assertThat(result.success()).isFalse();
        assertThat(loggedMessages()).noneMatch(m ->
                m.contains(PROMPT_MARKER) || m.contains(RESPONSE_MARKER) || m.contains(FAKE_KEY));
    }

    @Test
    @DisplayName("HTTP error degrades gracefully without logging body, key, or exception message")
    void httpErrorIsNotLogged() {
        nextStatus = 500;
        nextResponseBody = ("{\"error\":{\"message\":\"boom " + RESPONSE_MARKER + "\"}}").getBytes(StandardCharsets.UTF_8);

        AiCompletionResult result = client(enabledProperties())
                .complete("Instruction " + PROMPT_MARKER, List.of("Question " + PROMPT_MARKER));

        assertThat(result.success()).isFalse();
        assertThat(loggedMessages()).noneMatch(m ->
                m.contains(PROMPT_MARKER) || m.contains(RESPONSE_MARKER) || m.contains(FAKE_KEY));
    }

    @Test
    @DisplayName("disabled configuration performs no network call")
    void disabledPerformsNoNetworkCall() {
        AiProperties props = enabledProperties();
        props.setEnabled(false);

        AiCompletionResult result = client(props).complete("sys", List.of("q"));

        assertThat(result.success()).isFalse();
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("blank API key performs no network call")
    void blankKeyPerformsNoNetworkCall() {
        AiProperties props = enabledProperties();
        props.setApiKey("   ");

        AiCompletionResult result = client(props).complete("sys", List.of("q"));

        assertThat(result.success()).isFalse();
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("unresolved ${...} placeholder key is treated as unconfigured (no network call)")
    void placeholderKeyPerformsNoNetworkCall() {
        AiProperties props = enabledProperties();
        props.setApiKey("${GEMINI_API_KEY}");

        AiCompletionResult result = client(props).complete("sys", List.of("q"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("api key");
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("isApiKeyConfigured rejects blank and placeholder literals")
    void apiKeyConfiguredGuard() {
        assertThat(GeminiCompletionClient.isApiKeyConfigured(null)).isFalse();
        assertThat(GeminiCompletionClient.isApiKeyConfigured("")).isFalse();
        assertThat(GeminiCompletionClient.isApiKeyConfigured("   ")).isFalse();
        assertThat(GeminiCompletionClient.isApiKeyConfigured("${GEMINI_API_KEY}")).isFalse();
        assertThat(GeminiCompletionClient.isApiKeyConfigured("  ${AI_API_KEY}  ")).isFalse();
        assertThat(GeminiCompletionClient.isApiKeyConfigured("real-key-value")).isTrue();
    }
}
