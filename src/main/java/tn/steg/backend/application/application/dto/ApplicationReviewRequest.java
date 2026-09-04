package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tn.steg.backend.application.domain.model.ApplicationStatus;

@Schema(description = "Staff-initiated status transition payload")
public record ApplicationReviewRequest(

        @NotNull(message = "Target status is required")
        @Schema(description = "New status to set",
                allowableValues = {"UNDER_REVIEW", "ACCEPTED", "REJECTED", "NEEDS_CORRECTION"})
        ApplicationStatus targetStatus,

        @Schema(description = "Rejection reason (required when targetStatus = REJECTED)")
        String rejectionReason,

        @Schema(description = "Correction comment (required when targetStatus = NEEDS_CORRECTION)")
        String correctionComment
) {}
