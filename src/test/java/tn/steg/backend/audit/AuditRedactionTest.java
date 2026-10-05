package tn.steg.backend.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.audit.application.AuditService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9 audit redaction (§8.2/§10): payloads must never carry secrets. Pure
 * unit tests over the redaction function (no Spring) plus shape checks that
 * non-sensitive data survives untouched.
 */
@DisplayName("S9 — audit payload redaction")
class AuditRedactionTest {

    @Test
    @DisplayName("credential-like keys are redacted, recursively through maps and lists")
    void secretsAreRedacted() {
        Object redacted = AuditService.redact(Map.of(
                "reference", "CERT-2026-00001",
                "password", "hunter2",
                "temporaryPassword", "Abc123!@#",
                "serviceToken", "tok-123",
                "X-Service-Token", "tok-123",
                "nationalIdHash", "aGVsbG8=",
                "nested", Map.of("apiKey", "sk-live", "amount", "150.00"),
                "items", List.of(Map.of("privateKey", "PEM", "ok", true))));

        assertThat(redacted.toString()).doesNotContain("hunter2", "tok-123", "aGVsbG8=", "sk-live", "PEM");
        assertThat(redacted.toString()).contains(
                "CERT-2026-00001", "150.00", "[REDACTED]");
    }

    @Test
    @DisplayName("ordinary business payloads pass through byte-identical")
    void businessPayloadsSurvive() {
        Map<String, Object> payload = Map.of(
                "reference", "PAY-2026-00007",
                "amount", "150.00",
                "status", "VALIDATED",
                "comment", "Add week 4");
        assertThat(AuditService.redact(payload)).isEqualTo(payload);
        assertThat(AuditService.redact(null)).isNull();
        assertThat(AuditService.redact("plain")).isEqualTo("plain");
    }

    @Test
    @DisplayName("key matching is case-insensitive and substring-based")
    void matchingIsCaseInsensitive() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) AuditService.redact(Map.of(
                "Password", "a",
                "ACCESS_TOKEN", "b",
                "clientSecret", "c",
                "Authorization", "d",
                "passphrase", "e"));
        assertThat(redacted.values()).containsOnly("[REDACTED]", "[REDACTED]", "[REDACTED]",
                "[REDACTED]", "e");
        // "passphrase" contains "pass" but not "password"/"passwd"/"pwd" — kept.
        assertThat(redacted.get("passphrase")).isEqualTo("e");
    }
}
