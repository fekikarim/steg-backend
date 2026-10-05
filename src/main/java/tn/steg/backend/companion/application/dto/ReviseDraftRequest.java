package tn.steg.backend.companion.application.dto;

/**
 * AI revision of one draft. The instruction is treated as edit data for this
 * draft only; it is never logged or audited.
 */
public record ReviseDraftRequest(
        String instruction
) {
}
