package tn.steg.backend.evaluation.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvaluationDomainRepositoryAdapter implements EvaluationDomainRepository {

    private final JpaEvaluationRepository jpa;

    @Override
    public Optional<Evaluation> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public Page<Evaluation> findByInternshipId(UUID internshipId, Pageable pageable) {
        return jpa.findByInternshipId(internshipId, pageable);
    }

    @Override
    public List<Evaluation> findByInternshipIdAndType(UUID internshipId, EvaluationType type) {
        return jpa.findByInternshipIdAndType(internshipId, type);
    }

    @Override
    public Evaluation save(Evaluation evaluation) {
        return jpa.save(evaluation);
    }
}
