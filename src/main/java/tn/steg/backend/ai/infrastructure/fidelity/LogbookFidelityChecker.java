package tn.steg.backend.ai.infrastructure.fidelity;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.service.LogbookFidelityGate;

/**
 * Logbook integrity gate (E5.5 — critical).
 *
 * <p>Post-generation check: every ISO date in the AI draft must exist in the
 * source records, and every draft line must lexically map to a source entry
 * (token overlap). Unmapped lines are stripped and flagged — never rendered
 * as official content. Output stays editable; only a supervisor-validated
 * logbook is official.
 */
@Component
public class LogbookFidelityChecker implements LogbookFidelityGate {

    // SourceEntry/CheckResult inherited from the domain port



    @Override
    public LogbookFidelityGate.CheckResult check(String draft, List<LogbookFidelityGate.SourceEntry> sources) {
        if (draft == null || draft.isBlank() || sources == null || sources.isEmpty()) {
            return new LogbookFidelityGate.CheckResult(draft == null ? "" : draft, List.of(), false);
        }
        Set<String> sourceDates = new HashSet<>();
        Set<String> sourceTokens = new HashSet<>();
        for (LogbookFidelityGate.SourceEntry s : sources) {
            if (s.date() != null) {
                sourceDates.add(s.date().toString());
            }
            sourceTokens.addAll(tokens(s.text()));
        }
        String[] lines = draft.split("\\R");
        StringBuilder kept = new StringBuilder();
        List<String> stripped = new java.util.ArrayList<>();
        java.util.regex.Matcher dateMatcher =
                java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher("");
        for (String line : lines) {
            dateMatcher.reset(line);
            boolean dateOk = true;
            while (dateMatcher.find()) {
                if (!sourceDates.contains(dateMatcher.group())) {
                    dateOk = false;
                    break;
                }
            }
            Set<String> lineTokens = tokens(line);
            lineTokens.retainAll(sourceTokens);
            boolean taskOk = line.isBlank() || lineTokens.size() >= 2 || line.trim().startsWith("|") && lineTokens.size() >= 1;
            if (dateOk && taskOk) {
                kept.append(line).append("\n");
            } else {
                stripped.add(line.strip());
            }
        }
        String filtered = kept.toString().strip();
        return new LogbookFidelityGate.CheckResult(filtered, List.copyOf(stripped), stripped.isEmpty());
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) {
            return out;
        }
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-zà-ÿ0-9]+")) {
            if (w.length() >= 4) {
                out.add(w);
            }
        }
        return out;
    }
}
