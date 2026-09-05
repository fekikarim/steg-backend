package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationScore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluationScoreResponse(
        UUID id,
        UUID evaluationId,
        UUID criterionId,
        String criterionName,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal weight,
        String comment,
        Instant createdAt,
        Instant updatedAt
) {
    public static EvaluationScoreResponse from(EvaluationScore s) {
        return new EvaluationScoreResponse(
                s.getId(),
                s.getEvaluation() != null ? s.getEvaluation().getId() : null,
                s.getCriterion() != null ? s.getCriterion().getId() : null,
                s.getCriterion() != null ? s.getCriterion().getName() : null,
                s.getScore(),
                s.getCriterion() != null ? s.getCriterion().getMaxScore() : null,
                s.getCriterion() != null ? s.getCriterion().getWeight() : null,
                s.getComment(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
