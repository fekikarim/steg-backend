package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload for creating a new internship application (creates in DRAFT status)")
public record ApplicationCreateRequest(

        @Schema(description = "Desired internship start date")
        LocalDate desiredStartDate,

        @Schema(description = "Desired internship end date")
        LocalDate desiredEndDate,

        @Schema(description = "Proposed internship theme / project topic")
        String proposedTheme,

        @Schema(description = "Whether submitted online (defaults to true)")
        Boolean submittedOnline
) {}
