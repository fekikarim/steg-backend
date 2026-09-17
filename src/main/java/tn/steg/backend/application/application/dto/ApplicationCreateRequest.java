package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Payload for creating a new internship application (creates in DRAFT status)")
public record ApplicationCreateRequest(

        @Schema(description = "Desired internship start date")
        LocalDate desiredStartDate,

        @Schema(description = "Desired internship end date")
        LocalDate desiredEndDate,

        @Schema(description = "Whether submitted online (defaults to true)")
        Boolean submittedOnline
) {}