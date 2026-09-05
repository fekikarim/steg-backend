package tn.steg.backend.evaluation.domain.repository;

import tn.steg.backend.evaluation.domain.model.EvaluationScore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — EvaluationScore persistence.
 */
public interface EvaluationScoreRepository {
    Optional<EvaluationScore> findById(UUID id);
    List<EvaluationScore> findByEvaluationId(UUID evaluationId);
    EvaluationScore save(EvaluationScore score);
    void deleteById(UUID id);
}
