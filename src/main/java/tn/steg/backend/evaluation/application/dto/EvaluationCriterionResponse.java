package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationCriterion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluationCriterionResponse(
        UUID id,
        UUID templateId,
        String name,
        String description,
        BigDecimal weight,
        BigDecimal maxScore,
        Instant createdAt,
        Instant updatedAt
) {
    public static EvaluationCriterionResponse from(EvaluationCriterion c) {
        return new EvaluationCriterionResponse(
                c.getId(),
                c.getTemplate() != null ? c.getTemplate().getId() : null,
                c.getName(),
                c.getDescription(),
                c.getWeight(),
                c.getMaxScore(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
