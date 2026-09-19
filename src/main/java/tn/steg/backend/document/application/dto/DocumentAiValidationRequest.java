package tn.steg.backend.document.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optional body for {@code POST /api/documents/{id}/validation}.
 * When omitted, the server derives the expected type from the stored
 * document and the candidate name from the caller's profile.
 */
@Schema(description = "Overrides for document AI validation; all fields optional")
public record DocumentAiValidationRequest(

        @Schema(description = "Expected document type, e.g. INTERNSHIP_APPLICATION or ASSIGNMENT_LETTER")
        String expectedType,

        @Schema(description = "Full candidate name to verify (e.g. \"Mohamed Ben Ali\"). When omitted the server uses the caller's profile name.")
        String fullName
) {}
