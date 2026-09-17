package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Payload for updating mutable fields of a DRAFT application")
public record ApplicationUpdateRequest(
        LocalDate desiredStartDate,
        LocalDate desiredEndDate,
        Boolean submittedOnline
) {}