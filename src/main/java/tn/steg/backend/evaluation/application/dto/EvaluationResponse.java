package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.model.EvaluationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EvaluationResponse(
        UUID id,
        UUID internshipId,
        UUID evaluatorId,
        String evaluatorEmail,
        UUID templateId,
        String templateName,
        EvaluationType type,
        LocalDate evaluationDate,
        String feedback,
        BigDecimal totalScore,
        Instant createdAt,
        Instant updatedAt
) {
    public static EvaluationResponse from(Evaluation e) {
        return new EvaluationResponse(
                e.getId(),
                e.getInternship() != null ? e.getInternship().getId() : null,
                e.getEvaluator() != null ? e.getEvaluator().getId() : null,
                e.getEvaluator() != null && e.getEvaluator().getUser() != null
                        ? e.getEvaluator().getUser().getEmail() : null,
                e.getTemplate() != null ? e.getTemplate().getId() : null,
                e.getTemplate() != null ? e.getTemplate().getName() : null,
                e.getType(),
                e.getEvaluationDate(),
                e.getFeedback(),
                e.getTotalScore(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
