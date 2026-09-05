package tn.steg.backend.evaluation.domain.repository;

import tn.steg.backend.evaluation.domain.model.EvaluationTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — EvaluationTemplate persistence.
 */
public interface EvaluationTemplateRepository {
    Optional<EvaluationTemplate> findById(UUID id);
    List<EvaluationTemplate> findAll();
    List<EvaluationTemplate> findAllActive();
    EvaluationTemplate save(EvaluationTemplate template);
}
