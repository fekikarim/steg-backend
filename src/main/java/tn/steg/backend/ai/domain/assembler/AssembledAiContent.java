package tn.steg.backend.ai.domain.assembler;

import java.util.List;

/**
 * Bounded, sanitized payload assembled for an AI completion query.
 * Contains only non-restricted content; CIN/NATIONAL_ID is structurally excluded.
 */
public record AssembledAiContent(
        String systemInstruction,
        List<String> promptParts,
        String inputSummary,
        boolean cinExcluded
) {
    public AssembledAiContent {
        if (!cinExcluded) {
            throw new IllegalArgumentException("cinExcluded MUST be true for all STEG AI assembled content.");
        }
    }
}
