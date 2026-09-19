package tn.steg.backend.document.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Structured result of the Python document-intelligence validation.
 * Produced by Spring Boot after securely calling the Python service;
 * browsers never contact Python directly.
 */
@Schema(description = "Document AI validation result (file type + candidate name)")
public record DocumentAiValidationResponse(

        @Schema(description = "Document id when validation ran against a stored document; null for ad-hoc public validation")
        UUID documentId,

        @Schema(description = "Overall validity (type AND name both valid)", example = "true")
        boolean valid,

        @Schema(description = "Expected document-type keywords were found", example = "true")
        boolean documentTypeValid,

        @Schema(description = "Candidate full name (first+last in either order) was found verbatim/fuzzy in the text", example = "true")
        boolean candidateNameValid,

        @Schema(description = "Confidence 0..1", example = "0.97")
        double confidence,

        @Schema(description = "Human-readable reason (field-level only, never document content)")
        String reason,

        @Schema(description = "Service contract version, currently \"1\"", example = "1")
        String version
) {}
