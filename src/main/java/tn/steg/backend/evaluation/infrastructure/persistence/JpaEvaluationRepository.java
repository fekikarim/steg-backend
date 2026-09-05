package tn.steg.backend.evaluation.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.model.EvaluationType;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaEvaluationRepository extends JpaRepository<Evaluation, UUID> {
    Page<Evaluation> findByInternshipId(UUID internshipId, Pageable pageable);
    List<Evaluation> findByInternshipIdAndType(UUID internshipId, EvaluationType type);
}
