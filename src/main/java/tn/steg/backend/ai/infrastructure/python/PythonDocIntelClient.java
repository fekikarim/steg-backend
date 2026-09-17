package tn.steg.backend.ai.infrastructure.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tn.steg.backend.ai.domain.client.DocIntelClient;

/**
 * Resilient client for the Python FastAPI document-intelligence service.
 *
 * <p>Guarantees: shared-token auth, strict timeouts, bounded retries with backoff,
 * circuit-breaking (fast-degrade after consecutive failures), strict response
 * validation (non-conforming JSON rejected, never rendered raw), field-level audit
 * logging only (never document content). Never throws to business callers — every
 * method returns {@code Optional.empty()} on degraded paths so workflows continue
 * with a clear "AI analysis unavailable" state.
 */
@Slf4j
@Component
public class PythonDocIntelClient implements DocIntelClient {

    static final String SERVICE_VERSION = "1";

    private final PythonProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public PythonDocIntelClient(PythonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        int timeout = props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 15000;
        rf.setConnectTimeout(Duration.ofMillis(timeout));
        rf.setReadTimeout(Duration.ofMillis(timeout));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (!props.getBaseUrl().isBlank()) {
            builder = builder.baseUrl(props.getBaseUrl());
        }
        this.restClient = builder.build();
    }

    public boolean isConfigured() {
        return !props.getBaseUrl().isBlank() && !props.getServiceToken().isBlank();
    }

    public boolean circuitOpen() {
        return consecutiveFailures.get() >= props.getCircuitBreakerThreshold();
    }

    /** Smart document detector: type keywords + candidate-name match (+ OCR fallback server-side). */
        @Override
    public Optional<DocIntelClient.DocumentValidation> validateDocument(byte[] pdf, String expectedType, String fullName) {
        if (!guard("validateDocument")) {
            return Optional.empty();
        }
        try {
            String body = objectMapper.writeValueAsString(new ValidationPayload(
                    Base64.getEncoder().encodeToString(pdf), expectedType, fullName, SERVICE_VERSION));
            JsonNode root = post("/api/doc-intel/validate", body);
            if (root == null || !root.hasNonNull("valid") || !root.hasNonNull("documentTypeValid")
                    || !root.hasNonNull("candidateNameValid") || !root.hasNonNull("confidence")
                    || !root.hasNonNull("reason")
                    || !"1".equals(root.path("version").asText("1"))) {
                log.warn("Python doc-intel rejected: non-conforming response (field-level only)");
                return Optional.empty();
            }
            ok();
            return Optional.of(new DocIntelClient.DocumentValidation(
                    root.path("valid").asText(), root.path("documentTypeValid").asBoolean(),
                    root.path("candidateNameValid").asBoolean(), root.path("confidence").asDouble(),
                    root.path("reason").asText()));
        } catch (Exception ex) {
            fail("validateDocument", ex);
            return Optional.empty();
        }
    }

    /** Advisory report analysis: STEG-focus relevance, never an acceptance decision. */
        @Override
    public Optional<DocIntelClient.ReportAnalysis> analyzeReport(byte[] pdf, String applicationReference) {
        if (!guard("analyzeReport")) {
            return Optional.empty();
        }
        try {
            String body = objectMapper.writeValueAsString(new ReportPayload(
                    Base64.getEncoder().encodeToString(pdf), applicationReference, SERVICE_VERSION));
            JsonNode root = post("/api/report/analyze", body);
            if (root == null || !root.hasNonNull("relevanceScore") || !root.hasNonNull("summary")
                    || !"1".equals(root.path("version").asText("1"))) {
                log.warn("Python report analysis rejected: non-conforming response");
                return Optional.empty();
            }
            ok();
            return Optional.of(new DocIntelClient.ReportAnalysis(root.path("relevanceScore").asDouble(),
                    root.path("detectedStegContent").asText(""), root.path("detectedActivities").asText(""),
                    root.path("missingElements").asText(""), root.path("summary").asText(""),
                    root.path("explanation").asText(""), root.path("confidence").asDouble(0)));
        } catch (Exception ex) {
            fail("analyzeReport", ex);
            return Optional.empty();
        }
    }

    /** Advisory finance verification: VERIFIED / NEEDS_REVIEW / INVALID + reasons. */
        @Override
    public Optional<DocIntelClient.FinanceVerification> verifyFinance(byte[] certificatePdf, byte[] reportPdf,
            String internFullName, String period, String internshipType) {
        if (!guard("verifyFinance")) {
            return Optional.empty();
        }
        try {
            String body = objectMapper.writeValueAsString(new FinancePayload(
                    certificatePdf == null ? null : Base64.getEncoder().encodeToString(certificatePdf),
                    reportPdf == null ? null : Base64.getEncoder().encodeToString(reportPdf),
                    internFullName, period, internshipType, SERVICE_VERSION));
            JsonNode root = post("/api/finance/verify", body);
            if (root == null || !root.hasNonNull("verdict") || !root.hasNonNull("reasons")
                    || !"1".equals(root.path("version").asText("1"))) {
                log.warn("Python finance verification rejected: non-conforming response");
                return Optional.empty();
            }
            String verdict = root.path("verdict").asText("");
            if (!verdict.equals("VERIFIED") && !verdict.equals("NEEDS_REVIEW") && !verdict.equals("INVALID")) {
                log.warn("Python finance verification rejected: unknown verdict");
                return Optional.empty();
            }
            ok();
            return Optional.of(new DocIntelClient.FinanceVerification(verdict, root.path("reasons").asText(""),
                    root.path("confidence").asDouble(0)));
        } catch (Exception ex) {
            fail("verifyFinance", ex);
            return Optional.empty();
        }
    }

    private boolean guard(String op) {
        if (!isConfigured()) {
            log.debug("Python AI service not configured; degrading {} (no call)", op);
            return false;
        }
        if (circuitOpen()) {
            log.warn("Python AI circuit open; degrading {} (failures={})", op, consecutiveFailures.get());
            return false;
        }
        return true;
    }

    private JsonNode post(String path, String json) throws Exception {
        Exception last = null;
        int attempts = Math.max(1, props.getMaxAttempts());
        for (int i = 1; i <= attempts; i++) {
            try {
                String response = restClient.post().uri(path)
                        .header("X-Service-Token", props.getServiceToken())
                        .body(json).retrieve().body(String.class);
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("empty response");
                }
                return objectMapper.readTree(response);
            } catch (Exception ex) {
                last = ex;
                if (i < attempts) {
                    Thread.sleep(300L * (1L << (i - 1)));
                }
            }
        }
        throw last != null ? last : new IllegalStateException("python call failed");
    }

    private void ok() {
        consecutiveFailures.set(0);
    }

    private void fail(String op, Exception ex) {
        int n = consecutiveFailures.incrementAndGet();
        String status = (ex instanceof HttpStatusCodeException h) ? String.valueOf(h.getStatusCode().value()) : "n/a";
        log.warn("Python AI call degraded: op={} errorClass={} httpStatus={} failures={}", op,
                ex.getClass().getSimpleName(), status, n);
    }

    private record ValidationPayload(String pdfBase64, String expectedType, String fullName, String version) {}

    private record ReportPayload(String pdfBase64, String applicationReference, String version) {}

    private record FinancePayload(String certificatePdfBase64, String reportPdfBase64, String internFullName,
            String period, String internshipType, String version) {}
}
