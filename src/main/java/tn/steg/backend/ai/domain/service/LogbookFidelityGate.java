package tn.steg.backend.ai.domain.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Post-generation integrity gate for logbooks (domain port, E1.2/E5.5).
 * Infrastructure provides the deterministic implementation; the application
 * service depends only on this port.
 */
public interface LogbookFidelityGate {

    record SourceEntry(LocalDate date, String text) {}

    record CheckResult(String filteredText, List<String> strippedLines, boolean clean) {}

    CheckResult check(String draft, List<SourceEntry> sources);
}
