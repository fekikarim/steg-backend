package tn.steg.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.infrastructure.client.GeminiCompletionClient;
import tn.steg.backend.ai.infrastructure.config.AiProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase A12 closure — ONE real Gemini verification, strictly opt-in.
 *
 * <p>Isolation guarantees (normal CI never needs a live credential):
 * <ul>
 *   <li>class name ends with {@code IT}, so Maven Surefire ({@code mvn test} /
 *       {@code mvn verify} default includes) never executes it;</li>
 *   <li>additionally gated on {@code AI_LIVE_VERIFY=true};</li>
 *   <li>no Spring context, no database, no persisted business data.</li>
 * </ul>
 *
 * <p>Run explicitly with environment-provided configuration only:
 * <pre>
 *   AI_LIVE_VERIFY=true ./mvnw -Dtest='GeminiLiveVerificationIT' \
 *       -DfailIfNoTests=false test
 * </pre>
 * with {@code GEMINI_API_KEY} (or {@code AI_API_KEY}) and optionally
 * {@code AI_MODEL} exported in the environment. The key is read exclusively
 * from the environment, never hard-coded, and never printed or logged — this
 * test asserts only metadata (provider, model, success flag, content length,
 * elapsed time).
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_VERIFY", matches = "true")
@DisplayName("A12 — Gemini live verification (opt-in, real API, no persistence)")
class GeminiLiveVerificationIT {

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Test
    @DisplayName("configured model performs one minimal real completion within timeout")
    void liveCompletionSucceeds() {
        String apiKey = System.getenv("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "Live verification requires GEMINI_API_KEY (or AI_API_KEY) in the environment.");

        AiProperties props = new AiProperties();
        props.setEnabled(true);
        props.setApiKey(apiKey);
        props.setModel(envOrDefault("AI_MODEL", "gemini-3.8-flash"));
        props.setBaseUrl(envOrDefault("AI_BASE_URL", "https://generativelanguage.googleapis.com"));
        props.setTimeoutMs(10_000);
        props.setMaxOutputTokens(256);
        props.setTemperature(0.0);

        GeminiCompletionClient client = new GeminiCompletionClient(props, new ObjectMapper());

        long started = System.currentTimeMillis();
        AiCompletionResult result = client.complete(
                "You are a connectivity probe. Reply with exactly: PONG",
                List.of("Reply with exactly: PONG"));
        long elapsedMs = System.currentTimeMillis() - started;

        // Metadata only — never the prompt, response text, key, or timing internals beyond the total.
        System.out.println("[LIVE-VERIFY] provider=" + result.provider()
                + " model=" + result.modelName()
                + " success=" + result.success()
                + " elapsedMs=" + elapsedMs);

        assertThat(result.success())
                .as("Live Gemini completion failed: %s", result.errorMessage())
                .isTrue();
        assertThat(result.content()).isNotBlank();
        System.out.println("[LIVE-VERIFY] contentLength=" + result.content().length());
        assertThat(elapsedMs).isLessThan(60_000L);
    }
}
