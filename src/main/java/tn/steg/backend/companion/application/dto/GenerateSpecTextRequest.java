package tn.steg.backend.companion.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * T05 text-path generation request (SU-TASK-02): the supervisor pastes a
 * specification instead of uploading a PDF. Feeds the exact same
 * validation/scope/rate-limit/audit pipeline — the text is data, never
 * instructions.
 */
public record GenerateSpecTextRequest(
        @NotNull UUID internshipId,
        String specText
) {
}
