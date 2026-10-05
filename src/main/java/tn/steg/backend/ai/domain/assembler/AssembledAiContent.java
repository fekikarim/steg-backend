package tn.steg.backend.ai.domain.assembler;

import java.util.List;

/**
 * Bounded, sanitized payload assembled for an AI completion query.
 * Contains only non-restricted content; CIN/NATIONAL_ID is structurally excluded.
 *
 * @param deterministicFallback optional ready-made answer composed purely from
 *        the assembled backend aggregates (no language model). When the AI
 *        provider is unavailable, services may return this instead of an error
 *        so the feature degrades to a rule-based assistant — never a broken
 *        screen. Null when the query type has no meaningful fallback.
 */
public record AssembledAiContent(
        String systemInstruction,
        List<String> promptParts,
        String inputSummary,
        boolean cinExcluded,
        String deterministicFallback
) {
    public AssembledAiContent {
        if (!cinExcluded) {
            throw new IllegalArgumentException("cinExcluded MUST be true for all STEG AI assembled content.");
        }
    }

    /** Convenience constructor for assemblers without a fallback answer. */
    public AssembledAiContent(String systemInstruction, List<String> promptParts,
                              String inputSummary, boolean cinExcluded) {
        this(systemInstruction, promptParts, inputSummary, cinExcluded, null);
    }
}
