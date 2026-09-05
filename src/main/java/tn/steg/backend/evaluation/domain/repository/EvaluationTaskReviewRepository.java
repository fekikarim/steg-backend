package tn.steg.backend.evaluation.domain.repository;

import tn.steg.backend.evaluation.domain.model.EvaluationTaskReview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — EvaluationTaskReview persistence.
 */
public interface EvaluationTaskReviewRepository {
    Optional<EvaluationTaskReview> findById(UUID id);
    List<EvaluationTaskReview> findByEvaluationId(UUID evaluationId);
    boolean existsByEvaluationIdAndTaskId(UUID evaluationId, UUID taskId);
    EvaluationTaskReview save(EvaluationTaskReview review);
}
