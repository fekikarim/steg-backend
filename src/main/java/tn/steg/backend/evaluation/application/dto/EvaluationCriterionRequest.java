package tn.steg.backend.evaluation.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to create or update an EvaluationCriterion within a template.
 */
public record EvaluationCriterionRequest(
        String name,
        String description,
        BigDecimal weight,
        BigDecimal maxScore
) {}
