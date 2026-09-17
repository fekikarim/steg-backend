package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.application.domain.model.ApplicationStatus;

import java.time.Instant;
import java.util.List;

@Schema(description = "Public tracking view of a single application journey")
public record PublicApplicationTrackingResponse(

        @Schema(description = "Application reference")
        String reference,

        @Schema(description = "Current status")
        ApplicationStatus status,

        @Schema(description = "Internship type")
        String calculatedType,

        @Schema(description = "Requirement (OBLIGATOIRE/OPTIONAL)")
        String requirement,

        @Schema(description = "Status explanation")
        String statusExplanation,

        @Schema(description = "Suggested next action")
        String nextStep,

        @Schema(description = "Timeline entries")
        List<String> timeline,

        @Schema(description = "Document summary")
        List<String> documentsSummary
) {}
