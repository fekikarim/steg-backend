package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Payload to create an Internship from an accepted application")
public record InternshipCreateFromApplicationRequest(
        @NotNull(message = "applicationId is required")
        UUID applicationId,

        @Schema(description = "Explicit flag for observation internship: true for OBLIGATOIRE, false for OPTIONAL. If null, conservatively defaults to OPTIONAL.")
        Boolean observationObligatoire
) {}
