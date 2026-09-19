package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Result of identifier validation — tells whether any identifier is already linked to an existing application")
public record IdentifierValidationResponse(

        @Schema(description = "Whether all identifiers are unique (no existing application found)", example = "true")
        boolean valid,

        @Schema(description = "List of fields that are already associated with an existing application (email, nationalId, phone)", example = "[\"email\"]")
        List<String> duplicateFields,

        @Schema(description = "Human-readable message (safe, no sensitive data leaked)", example = "An application already exists for the provided email address.")
        String message
) {}
