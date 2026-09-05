package tn.steg.backend.evaluation.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.evaluation.domain.model.EvaluationCriterion;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaEvaluationCriterionRepository extends JpaRepository<EvaluationCriterion, UUID> {
    List<EvaluationCriterion> findByTemplateId(UUID templateId);
}
