package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Response envelope for AI advisory analysis and recommendations")
public record AiAnalysisResultResponse(
        AiAnalysisResponse analysis,
        List<AiRecommendationResponse> recommendations,
        String responseText
) {
}
