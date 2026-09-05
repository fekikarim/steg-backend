package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tn.steg.backend.ai.domain.model.AiRecommendationStatus;

@Schema(description = "Request to review an AI recommendation")
public record AiRecommendationReviewRequest(
        @NotNull(message = "Review status must be specified (ACCEPTED_BY_HUMAN or DISMISSED)")
        AiRecommendationStatus status
) {
}
