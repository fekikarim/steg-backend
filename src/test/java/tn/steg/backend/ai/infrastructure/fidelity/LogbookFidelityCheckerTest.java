package tn.steg.backend.ai.infrastructure.fidelity;

import static org.assertj.core.api.Assertions.assertThat;

import tn.steg.backend.ai.domain.service.LogbookFidelityGate;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Logbook fidelity gate")
class LogbookFidelityCheckerTest {

    private final LogbookFidelityChecker checker = new LogbookFidelityChecker();

    @Test
    @DisplayName("keeps mapped lines, strips invented dates and unmapped lines")
    void stripsUnmappedContent() {
        var sources = List.of(
                new LogbookFidelityGate.SourceEntry(LocalDate.parse("2026-03-02"), "Configuration réseau au département"),
                new LogbookFidelityGate.SourceEntry(LocalDate.parse("2026-03-03"), "Rapport journalier supervision"));
        String draft = "- 2026-03-02 : Configuration réseau au département\n"
                + "- 2026-03-09 : Migration cloud terminée avec succès\n"
                + "- 2026-03-03 : Rapport journalier supervision";
        var result = checker.check(draft, sources);
        assertThat(result.clean()).isFalse();
        assertThat(result.filteredText()).contains("2026-03-02").contains("2026-03-03");
        assertThat(result.filteredText()).doesNotContain("2026-03-09");
        assertThat(result.strippedLines()).hasSize(1);
    }

    @Test
    @DisplayName("clean draft passes untouched")
    void cleanDraftPasses() {
        var sources = List.of(
                new LogbookFidelityGate.SourceEntry(LocalDate.parse("2026-03-02"), "Configuration réseau"));
        var result = checker.check("- 2026-03-02 : Configuration réseau", sources);
        assertThat(result.clean()).isTrue();
        assertThat(result.strippedLines()).isEmpty();
    }
}
