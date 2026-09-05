package tn.steg.backend.evaluation.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.evaluation.domain.model.EvaluationTaskReview;
import tn.steg.backend.evaluation.domain.repository.EvaluationTaskReviewRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvaluationTaskReviewRepositoryAdapter implements EvaluationTaskReviewRepository {

    private final JpaEvaluationTaskReviewRepository jpa;

    @Override
    public Optional<EvaluationTaskReview> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<EvaluationTaskReview> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId);
    }

    @Override
    public boolean existsByEvaluationIdAndTaskId(UUID evaluationId, UUID taskId) {
        return jpa.existsByEvaluationIdAndTaskId(evaluationId, taskId);
    }

    @Override
    public EvaluationTaskReview save(EvaluationTaskReview review) {
        return jpa.save(review);
    }
}
