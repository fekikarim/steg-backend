package tn.steg.backend.ai.infrastructure.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.ai.domain.client.DocIntelClient;
import tn.steg.backend.common.domain.exception.BusinessRuleException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S7 python-ai verification contract (§7.1–§7.3) against a JDK-local stub
 * (no Spring context, no network, no real credentials).
 *
 * <p>Proves the adapter speaks {@code POST /api/verification/report} and
 * {@code POST /api/verification/journal} with the shared
 * {@code X-Service-Token}, parses the structured
 * {@code {overall, checks[]}} contract strictly (non-conforming JSON
 * degrades to empty, never trusted), degrades to empty when the service is
 * unreachable, and surfaces a bad token as {@code AI_UNAVAILABLE} without
 * tripping the outage circuit.
 */
@DisplayName("PythonDocIntelClient — S7 verification contract")
class PythonDocIntelVerificationTest {

    private static final byte[] PDF = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    private HttpServer stubServer;
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private volatile byte[] nextResponseBody = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile int nextStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress(0), 0);
        stubServer.createContext("/", exchange -> {
            List<String> tokens = exchange.getRequestHeaders().get("X-Service-Token");
            receivedTokens.add(tokens == null || tokens.isEmpty() ? "" : tokens.get(0));
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = nextResponseBody;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stubServer.setExecutor(Executors.newSingleThreadExecutor());
        stubServer.start();
    }

    @AfterEach
    void tearDown() {
        stubServer.stop(0);
    }

    private PythonDocIntelClient client(String token) {
        PythonProperties props = new PythonProperties();
        props.setBaseUrl("http://localhost:" + stubServer.getAddress().getPort());
        props.setServiceToken(token);
        props.setTimeoutMs(5000);
        props.setMaxAttempts(1);
        return new PythonDocIntelClient(props, new ObjectMapper());
    }

    private DocIntelClient.ReportExpected reportExpected() {
        return new DocIntelClient.ReportExpected("Sami Gharbi", "Leila Ben Ammar", "PFE",
                "2026-01-01", "2026-04-01", "INSAT Tunis");
    }

    private static String passReportJson() {
        return """
                {"overall":"PASS","checks":[
                {"key":"candidate_name","label":"Candidate name","status":"PASSED","expected":"Sami Gharbi","found":"Sami Gharbi","evidence":"p1"},
                {"key":"page_count","label":"Page count","status":"PASSED","expected":">= 4","found":"6","evidence":"ok"}]}""";
    }

    private static String failJournalJson() {
        return """
                {"overall":"FAIL","checks":[
                {"key":"task_completion","label":"Task completion","status":"FAILED","expected":">= 75%","found":"2/8 (25%)","evidence":"ratio"},
                {"key":"structure","label":"Journal structure","status":"PASSED","expected":"period section and task table","found":"both","evidence":"ok"}]}""";
    }

    private static String inconclusiveJson() {
        return """
                {"overall":"INCONCLUSIVE","checks":[
                {"key":"candidate_name","label":"Candidate name","status":"INCONCLUSIVE","expected":"","found":"","evidence":"PDF text could not be extracted."}]}""";
    }

    @Test
    @DisplayName("report PASS: token header sent, checks parsed check by check")
    void reportPassParsed() {
        nextResponseBody = passReportJson().getBytes(StandardCharsets.UTF_8);
        Optional<DocIntelClient.VerificationResult> result =
                client("test-token").verifyReport(PDF, reportExpected());

        assertThat(result).isPresent();
        assertThat(result.get().overall()).isEqualTo("PASS");
        assertThat(result.get().checks()).hasSize(2);
        assertThat(result.get().checks().get(0).status()).isEqualTo("PASSED");
        assertThat(receivedTokens).containsExactly("test-token");
        assertThat(receivedBodies.get(0)).contains("Sami Gharbi");
    }

    @Test
    @DisplayName("journal FAIL (low completion ratio) parses with FAILED check statuses")
    void journalFailParsed() {
        nextResponseBody = failJournalJson().getBytes(StandardCharsets.UTF_8);
        Optional<DocIntelClient.VerificationResult> result = client("test-token")
                .verifyJournal(PDF, new DocIntelClient.JournalExpected("2026-01-01", "2026-04-01", 2, 8));

        assertThat(result).isPresent();
        assertThat(result.get().overall()).isEqualTo("FAIL");
        assertThat(result.get().checks().get(0).status()).isEqualTo("FAILED");
        assertThat(receivedBodies.get(0)).contains("completedApprovedTasks");
    }

    @Test
    @DisplayName("scanned/unreadable PDF surfaces as INCONCLUSIVE, never a false FAILED")
    void scannedPdfIsInconclusive() {
        nextResponseBody = inconclusiveJson().getBytes(StandardCharsets.UTF_8);
        Optional<DocIntelClient.VerificationResult> result =
                client("test-token").verifyReport(PDF, reportExpected());

        assertThat(result).isPresent();
        assertThat(result.get().overall()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("non-conforming JSON degrades to empty (never trusted)")
    void nonConformingJsonDegrades() {
        nextResponseBody = "{\"surprise\":true}".getBytes(StandardCharsets.UTF_8);
        assertThat(client("test-token").verifyReport(PDF, reportExpected())).isEmpty();

        nextResponseBody = "{\"overall\":\"MAYBE\",\"checks\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(client("test-token")
                .verifyJournal(PDF, new DocIntelClient.JournalExpected("2026-01-01", "2026-04-01", 1, 1)))
                .isEmpty();
    }

    @Test
    @DisplayName("unreachable service degrades to empty (manual validation never blocked)")
    void unreachableServiceDegrades() {
        PythonProperties props = new PythonProperties();
        props.setBaseUrl("http://localhost:1");
        props.setServiceToken("test-token");
        props.setTimeoutMs(500);
        props.setMaxAttempts(1);
        PythonDocIntelClient down = new PythonDocIntelClient(props, new ObjectMapper());

        assertThat(down.verifyReport(PDF, reportExpected())).isEmpty();
        assertThat(down.verifyJournal(PDF,
                new DocIntelClient.JournalExpected("2026-01-01", "2026-04-01", 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("bad token surfaces AI_UNAVAILABLE without tripping the outage circuit")
    void badTokenIsUnavailable() {
        nextStatus = 401;
        nextResponseBody = "{\"error\":\"unauthorized caller\"}".getBytes(StandardCharsets.UTF_8);
        PythonDocIntelClient client = client("wrong-token");

        assertThatThrownBy(() -> client.verifyReport(PDF, reportExpected()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not configured");
        // Deterministic 4xx must not open the outage circuit.
        assertThat(client.circuitOpen()).isFalse();
    }

    @Test
    @DisplayName("shared golden fixture: the real service responses parse under the strict contract")
    void sharedFixtureResponsesAreAccepted() throws Exception {
        // One shared file, both sides: ai-services/tests/fixtures/
        // verification_contract.json pins the exact live responses, and the
        // Python suite asserts the live app returns them byte-for-byte.
        java.nio.file.Path fixture = java.nio.file.Paths.get(
                        System.getProperty("user.dir"))
                .resolve("../ai-services/tests/fixtures/verification_contract.json")
                .normalize();
        assertThat(java.nio.file.Files.isRegularFile(fixture))
                .as("shared fixture missing — run the full monorepo checkout: %s", fixture)
                .isTrue();
        com.fasterxml.jackson.databind.JsonNode root =
                new ObjectMapper().readTree(fixture.toFile());

        com.fasterxml.jackson.databind.JsonNode report = root.path("report").path("response");
        DocIntelClient.VerificationResult parsedReport =
                PythonDocIntelClient.parseVerificationResult(report);
        assertThat(parsedReport).isNotNull();
        assertThat(parsedReport.overall()).isEqualTo("PASS");
        assertThat(parsedReport.checks()).hasSize(7);
        assertThat(parsedReport.checks().stream().map(DocIntelClient.VerificationCheck::status))
                .containsOnly("PASSED");

        com.fasterxml.jackson.databind.JsonNode journal = root.path("journal").path("response");
        DocIntelClient.VerificationResult parsedJournal =
                PythonDocIntelClient.parseVerificationResult(journal);
        assertThat(parsedJournal).isNotNull();
        assertThat(parsedJournal.overall()).isEqualTo("PASS");
        assertThat(parsedJournal.checks()).hasSize(3);
    }
}
