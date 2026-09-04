package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload to manually register an internship directly for a candidate")
public record InternshipCreateManualRequest(
        @NotNull(message = "candidateId is required")
        UUID candidateId,

        @NotNull(message = "startDate is required")
        LocalDate startDate,

        @NotNull(message = "endDate is required")
        LocalDate endDate,

        @NotBlank(message = "subject is required")
        String subject,

        String academicLevel,

        @Schema(description = "Explicit flag for observation internship: true for OBLIGATOIRE, false for OPTIONAL. If null, conservatively defaults to OPTIONAL.")
        Boolean observationObligatoire
) {}
