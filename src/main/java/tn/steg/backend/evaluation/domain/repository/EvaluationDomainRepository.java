package tn.steg.backend.evaluation.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.model.EvaluationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — Evaluation persistence.
 */
public interface EvaluationDomainRepository {
    Optional<Evaluation> findById(UUID id);
    Page<Evaluation> findByInternshipId(UUID internshipId, Pageable pageable);
    List<Evaluation> findByInternshipIdAndType(UUID internshipId, EvaluationType type);
    Evaluation save(Evaluation evaluation);
}
