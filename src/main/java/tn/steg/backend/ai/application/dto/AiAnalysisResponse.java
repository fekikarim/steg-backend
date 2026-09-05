package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "AI Analysis summary response")
public record AiAnalysisResponse(
        UUID id,
        AiAnalysisType type,
        String relatedEntityType,
        UUID relatedEntityId,
        String modelUsed,
        String inputSummary,
        String outputSummary,
        boolean cinExcluded,
        Instant createdAt
) {
    public static AiAnalysisResponse from(AiAnalysis analysis) {
        return new AiAnalysisResponse(
                analysis.getId(),
                analysis.getType(),
                analysis.getRelatedEntityType(),
                analysis.getRelatedEntityId(),
                analysis.getModelUsed(),
                analysis.getInputSummary(),
                analysis.getOutputSummary(),
                analysis.getCinExcluded() != null ? analysis.getCinExcluded() : true,
                analysis.getCreatedAt()
        );
    }
}
