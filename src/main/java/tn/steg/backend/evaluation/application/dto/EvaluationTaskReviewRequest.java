package tn.steg.backend.evaluation.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to add/update a task review within an evaluation.
 */
public record EvaluationTaskReviewRequest(
        UUID taskId,
        Boolean completed,
        BigDecimal score,
        String comment
) {}
