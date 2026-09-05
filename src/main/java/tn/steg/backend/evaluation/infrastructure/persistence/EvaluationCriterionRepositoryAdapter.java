package tn.steg.backend.evaluation.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.evaluation.domain.model.EvaluationCriterion;
import tn.steg.backend.evaluation.domain.repository.EvaluationCriterionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvaluationCriterionRepositoryAdapter implements EvaluationCriterionRepository {

    private final JpaEvaluationCriterionRepository jpa;

    @Override
    public Optional<EvaluationCriterion> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<EvaluationCriterion> findByTemplateId(UUID templateId) {
        return jpa.findByTemplateId(templateId);
    }

    @Override
    public EvaluationCriterion save(EvaluationCriterion criterion) {
        return jpa.save(criterion);
    }

    @Override
    public void deleteById(UUID id) {
        jpa.deleteById(id);
    }
}
