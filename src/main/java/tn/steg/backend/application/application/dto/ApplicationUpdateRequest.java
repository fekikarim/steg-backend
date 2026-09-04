package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Payload for updating mutable fields of a DRAFT application")
public record ApplicationUpdateRequest(

        @Schema(description = "Desired internship start date")
        LocalDate desiredStartDate,

        @Schema(description = "Desired internship end date")
        LocalDate desiredEndDate,

        @Schema(description = "Proposed internship theme / project topic")
        String proposedTheme,

        @Schema(description = "Whether submitted online")
        Boolean submittedOnline
) {}
