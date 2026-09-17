package tn.steg.backend.ai.infrastructure.python;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Python AI document services (E2/E5): single FastAPI deployment behind the backend.
 * Never reachable from browsers/mobile — only Spring Boot calls it, authenticated
 * by the shared service token. All AI results stay advisory; humans decide.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "steg.python")
public class PythonProperties {

    /** Base URL of the FastAPI service, e.g. http://localhost:8000. Empty = disabled. */
    private String baseUrl = "";

    /** Shared service token sent as X-Service-Token. Never logged, never exposed. */
    private String serviceToken = "";

    /** Request timeout per attempt in milliseconds. */
    private int timeoutMs = 15000;

    /** Bounded retries (total attempts = maxAttempts). */
    private int maxAttempts = 2;

    /** Consecutive failures before the circuit opens (fast-degrade). */
    private int circuitBreakerThreshold = 5;

    /** Max PDF pages analysed per request (service enforces too). */
    private int maxPages = 20;

    /** Max PDF bytes per request. */
    private long maxBytes = 26214400L;
}
