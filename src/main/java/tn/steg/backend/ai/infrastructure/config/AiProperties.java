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
     */
    private String apiKey = "";

    /**
     * Model identifier (e.g. "gemini-3.8-flash").
     */
    private String model = "gemini-3.8-flash";

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
