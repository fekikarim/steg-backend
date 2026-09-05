package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to create a new Evaluation for an internship.
 * totalScore is intentionally excluded — it is computed server-side.
 */
public record EvaluationRequest(
        UUID templateId,
        EvaluationType type,
        LocalDate evaluationDate,
        String feedback
) {}
