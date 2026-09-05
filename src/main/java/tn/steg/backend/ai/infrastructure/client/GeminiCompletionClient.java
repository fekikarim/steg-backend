package tn.steg.backend.ai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.infrastructure.config.AiProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini implementation of AiCompletionClient using Spring Boot's RestClient
 * to communicate with Google Generative Language API v1beta.
 *
 * <p>Security & Robustness guarantees:
 * <ul>
 *   <li>Externalized API key via header/query parameter, never hard-coded.
 *   <li>API key is NEVER logged.
 *   <li>Prompts, response bodies, and business content are NEVER logged —
 *       only provider/model identifiers, HTTP status codes, exception class
 *       names, and response byte lengths are emitted as diagnostics.
 *   <li>Strict timeouts enforced (connect and read).
 *   <li>Catches all transport/HTTP exceptions and returns AiCompletionResult.failure(...) for graceful degradation.
 * </ul>
 */
@Slf4j
@Component
public class GeminiCompletionClient implements AiCompletionClient {

    private final AiProperties aiProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GeminiCompletionClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeout = aiProperties.getTimeoutMs() > 0 ? aiProperties.getTimeoutMs() : 10000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(aiProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public AiCompletionResult complete(String systemInstruction, List<String> userPrompts) {
        if (!aiProperties.isEnabled()) {
            log.info("AI is disabled by configuration (steg.ai.enabled=false). Skipping Gemini invocation.");
            return AiCompletionResult.failure("AI is currently disabled by system configuration.",
                    aiProperties.getModel(), getProvider());
        }

        String apiKey = aiProperties.getApiKey();
        if (!isApiKeyConfigured(apiKey)) {
            log.warn("Gemini API key is not configured. Degrading gracefully without AI output.");
            return AiCompletionResult.failure("AI service is not configured with an API key.",
                    aiProperties.getModel(), getProvider());
        }

        try {
            // Build Gemini v1beta generateContent payload
            Map<String, Object> requestPayload = new HashMap<>();

            // System instruction
            if (systemInstruction != null && !systemInstruction.isBlank()) {
                requestPayload.put("systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction))
                ));
            }

            // Contents (user prompts)
            List<Map<String, Object>> contents = new ArrayList<>();
            List<Map<String, String>> parts = new ArrayList<>();
            if (userPrompts != null) {
                for (String p : userPrompts) {
                    if (p != null && !p.isBlank()) {
                        parts.add(Map.of("text", p));
                    }
                }
            }
            if (parts.isEmpty()) {
                parts.add(Map.of("text", "Please provide guidance based on the context."));
            }
            contents.add(Map.of("role", "user", "parts", parts));
            requestPayload.put("contents", contents);

            // Generation config
            Map<String, Object> genConfig = new HashMap<>();
            genConfig.put("temperature", aiProperties.getTemperature());
            genConfig.put("maxOutputTokens", aiProperties.getMaxOutputTokens());
            requestPayload.put("generationConfig", genConfig);

            String requestJson = objectMapper.writeValueAsString(requestPayload);

            // POST /v1beta/models/{model}:generateContent?key={apiKey}
            String endpointPath = "/v1beta/models/" + aiProperties.getModel() + ":generateContent";

            log.debug("Sending completion request to Gemini model={}", aiProperties.getModel());

            String responseBody = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpointPath)
                            .queryParam("key", apiKey)
                            .build())
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return AiCompletionResult.failure("Received empty response from Gemini API.",
                        aiProperties.getModel(), getProvider());
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode partsNode = candidates.get(0).path("content").path("parts");
                if (partsNode.isArray() && !partsNode.isEmpty()) {
                    String generatedText = partsNode.get(0).path("text").asText();
                    return AiCompletionResult.success(generatedText, aiProperties.getModel(), getProvider());
                }
            }

            log.warn("Gemini response did not contain candidates/parts text: provider={} model={} responseLength={}",
                    getProvider(), aiProperties.getModel(), responseBody.length());
            return AiCompletionResult.failure("Model response did not contain text content.",
                    aiProperties.getModel(), getProvider());

        } catch (Exception ex) {
            // NEVER log the API key, prompts, response bodies, or exception messages
            // (HTTP error messages may echo response content). Class + status only.
            String httpStatus = (ex instanceof HttpStatusCodeException httpEx)
                    ? String.valueOf(httpEx.getStatusCode().value())
                    : "n/a";
            log.error("Gemini AI completion failed: provider={} model={} errorClass={} httpStatus={}",
                    getProvider(), aiProperties.getModel(), ex.getClass().getSimpleName(), httpStatus);
            return AiCompletionResult.failure("AI completion service temporarily unavailable: " + ex.getClass().getSimpleName(),
                    aiProperties.getModel(), getProvider());
        }
    }

    @Override
    public String getProvider() {
        return "gemini";
    }

    /**
     * Whether the given raw API-key value counts as a genuinely configured secret.
     *
     * <p>Blank values are unconfigured. Values that still look like an unresolved
     * Spring/shell placeholder (e.g. the literal string {@code "${GEMINI_API_KEY}"}
     * leaking in from a {@code .env} file without variable expansion) are also
     * treated as unconfigured, so the client degrades gracefully instead of
     * sending a placeholder literal to the provider as if it were a key.
     */
    static boolean isApiKeyConfigured(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String trimmed = apiKey.strip();
        return !(trimmed.startsWith("${") && trimmed.endsWith("}"));
    }

    @Override
    public String getModel() {
        return aiProperties.getModel();
    }
}
