package tn.steg.backend.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "steg.ai")
public class AiProperties {

    /**
     * Whether AI features are enabled. Defaults to false.
     */
    private boolean enabled = false;

    /**
     * AI provider identifier (e.g. "gemini").
     */
    private String provider = "gemini";

    /**
     * API key for the provider, injected from environment/secrets. Never hard-coded.
     *
     * <p>Resolution is forgiving: an environment variable that is exported but
     * EMPTY (e.g. {@code AI_API_KEY=} in .env) still shadows the nested
     * {@code ${GEMINI_API_KEY:}} placeholder in application.yml, which used to
     * disable every AI feature despite a configured key. When the bound value
     * is blank we therefore fall back to {@code GEMINI_API_KEY} from the
     * environment. The key never leaves the backend and is never logged.
     */
    private String apiKey = "";

    public String getApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        String fallback = System.getenv("GEMINI_API_KEY");
        return fallback != null ? fallback : "";
    }

    /**
     * Model identifier (e.g. "gemini-2.5-flash").
     *
     * <p>Resolution mirrors the API key: {@code AI_MODEL} wins, then
     * {@code GEMINI_MODEL}, then blank. There is deliberately NO hard-coded
     * default — callers must treat a blank model as "AI unavailable"
     * (degraded, never blocking). The key and the model never leave the
     * backend and are never logged.
     */
    private String model = "";

    public String getModel() {
        if (model != null && !model.isBlank()) {
            return model.strip();
        }
        String fallback = System.getenv("GEMINI_MODEL");
        return fallback != null ? fallback.strip() : "";
    }

    /**
     * Base URL for the provider API.
     */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * Request timeout in milliseconds.
     */
    private int timeoutMs = 10000;

    /**
     * Maximum output tokens.
     */
    private int maxOutputTokens = 2048;

    /**
     * Sampling temperature.
     */
    private double temperature = 0.2;
}
