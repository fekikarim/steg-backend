package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationTaskReview;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluationTaskReviewResponse(
        UUID id,
        UUID evaluationId,
        UUID taskId,
        String taskTitle,
        Boolean completed,
        BigDecimal score,
        String comment,
        Instant createdAt,
        Instant updatedAt
) {
    public static EvaluationTaskReviewResponse from(EvaluationTaskReview r) {
        return new EvaluationTaskReviewResponse(
                r.getId(),
                r.getEvaluation() != null ? r.getEvaluation().getId() : null,
                r.getTask() != null ? r.getTask().getId() : null,
                r.getTask() != null ? r.getTask().getTitle() : null,
                r.getCompleted(),
                r.getScore(),
                r.getComment(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
