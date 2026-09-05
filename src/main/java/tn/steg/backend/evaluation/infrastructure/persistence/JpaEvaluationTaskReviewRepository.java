package tn.steg.backend.evaluation.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.evaluation.domain.model.EvaluationTaskReview;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaEvaluationTaskReviewRepository extends JpaRepository<EvaluationTaskReview, UUID> {
    List<EvaluationTaskReview> findByEvaluationId(UUID evaluationId);
    boolean existsByEvaluationIdAndTaskId(UUID evaluationId, UUID taskId);
}
