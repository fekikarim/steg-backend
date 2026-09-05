package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationScore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluationScoreRequest(
        UUID criterionId,
        BigDecimal score,
        String comment
) {}
