package tn.steg.backend.document.infrastructure.scanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tn.steg.backend.common.domain.exception.BusinessRuleException;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deployment-readiness gate (verification plan §8): exercises
 * {@link ClamAvMalwareScanner} against a <b>real</b> {@code clamd} daemon
 * running in a Testcontainers-managed {@code clamav/clamav:stable} container
 * — no hand-rolled stub protocol. This complements (does not replace)
 * {@link ClamAvMalwareScannerTest}, whose raw-socket stub server emulates the
 * INSTREAM wire format but cannot catch protocol-shape mismatches against the
 * genuine daemon.
 *
 * <p><b>Real defect found by this test, now fixed in production code:</b> the
 * real {@code clamd} terminates its INSTREAM verdict with a trailing
 * {@code NUL} byte ({@code "stream: OK\0"}) and then closes the connection,
 * instead of a {@code '\n'} terminator. {@code ClamAvMalwareScanner.readLine(...)}
 * originally only stopped on {@code '\n'}, so against a real daemon it kept
 * reading until EOF and returned the verdict string including the trailing
 * NUL byte — which made {@code verdict.endsWith("OK")} always {@code false}
 * for a genuine clean-file verdict, so every legitimate upload would have
 * been rejected as {@code MALWARE_SCAN_FAILED} once
 * {@code steg.security.malware.mode=clamav} was active against a real clamd.
 * {@code readLine(...)} now stops on {@code '\n'} OR a NUL byte, and the
 * verdict is {@code .strip()}-ped before the {@code endsWith}/{@code contains}
 * checks. {@link #cleanPayloadIsAcceptedByRealClamd()} below now passes
 * against the genuine daemon, confirming the fix end-to-end.
 */
@Testcontainers
@DisplayName("ClamAV real-daemon container integration tests (deployment gate)")
class ClamAvContainerIntegrationTest {

    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav:stable"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\..*\\n", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private ClamAvMalwareScanner realScanner(String onError) {
        return new ClamAvMalwareScanner(CLAMAV.getHost(), CLAMAV.getMappedPort(3310), 15_000, 25_000_000L, onError);
    }

    @Test
    @DisplayName("A clean payload is accepted by a real clamd daemon")
    void cleanPayloadIsAcceptedByRealClamd() {
        byte[] payload = "%PDF-1.4 clean-content".getBytes(StandardCharsets.UTF_8);

        boolean clean = realScanner("reject").isClean(new ByteArrayInputStream(payload), "report.pdf");

        assertThat(clean).isTrue();
    }

    @Test
    @DisplayName("The EICAR standard test file is rejected by a real clamd daemon")
    void eicarPayloadIsRejectedByRealClamd() {
        byte[] payload = EICAR.getBytes(StandardCharsets.UTF_8);

        boolean clean = realScanner("reject").isClean(new ByteArrayInputStream(payload), "eicar.com");

        assertThat(clean).isFalse();
    }

    @Test
    @DisplayName("An unreachable scanner fails closed with MALWARE_SCAN_FAILED by default")
    void unreachableScannerFailsClosed() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        // freePort is now closed again (no listener) — connection attempts will be refused,
        // simulating clamd being down/unreachable without touching the running container.
        ClamAvMalwareScanner scanner = new ClamAvMalwareScanner(CLAMAV.getHost(), freePort, 2_000, 25_000_000L, "reject");

        assertThatThrownBy(() -> scanner.isClean(new ByteArrayInputStream(new byte[]{1, 2, 3}), "doc.pdf"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MALWARE_SCAN_FAILED");
    }
}
