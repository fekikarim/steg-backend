package tn.steg.backend.evaluation.domain.repository;

import tn.steg.backend.evaluation.domain.model.EvaluationCriterion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — EvaluationCriterion persistence.
 */
public interface EvaluationCriterionRepository {
    Optional<EvaluationCriterion> findById(UUID id);
    List<EvaluationCriterion> findByTemplateId(UUID templateId);
    EvaluationCriterion save(EvaluationCriterion criterion);
    void deleteById(UUID id);
}
