package tn.steg.backend.evaluation.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.evaluation.domain.model.EvaluationScore;
import tn.steg.backend.evaluation.domain.repository.EvaluationScoreRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvaluationScoreRepositoryAdapter implements EvaluationScoreRepository {

    private final JpaEvaluationScoreRepository jpa;

    @Override
    public Optional<EvaluationScore> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<EvaluationScore> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId);
    }

    @Override
    public EvaluationScore save(EvaluationScore score) {
        return jpa.save(score);
    }

    @Override
    public void deleteById(UUID id) {
        jpa.deleteById(id);
    }
}
