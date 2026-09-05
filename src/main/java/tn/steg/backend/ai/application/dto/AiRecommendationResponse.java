package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.ai.domain.model.AiRecommendation;
import tn.steg.backend.ai.domain.model.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "AI Recommendation response")
public record AiRecommendationResponse(
        UUID id,
        UUID analysisId,
        String recommendationText,
        AiRecommendationStatus status,
        UUID reviewedById,
        Instant reviewedAt,
        Instant createdAt
) {
    public static AiRecommendationResponse from(AiRecommendation rec) {
        return new AiRecommendationResponse(
                rec.getId(),
                rec.getAnalysis() != null ? rec.getAnalysis().getId() : null,
                rec.getRecommendationText(),
                rec.getStatus(),
                rec.getReviewedBy() != null ? rec.getReviewedBy().getId() : null,
                rec.getReviewedAt(),
                rec.getCreatedAt()
        );
    }
}
