package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "Payload to update internship dates, automatically triggering deterministic reclassification")
public record InternshipUpdateDatesRequest(
        @NotNull(message = "startDate is required")
        LocalDate startDate,

        @NotNull(message = "endDate is required")
        LocalDate endDate,

        @Schema(description = "Explicit flag for observation internship: true for OBLIGATOIRE, false for OPTIONAL.")
        Boolean observationObligatoire
) {}
