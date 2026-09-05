package tn.steg.backend.evaluation.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.evaluation.domain.model.EvaluationTemplate;
import tn.steg.backend.evaluation.domain.repository.EvaluationTemplateRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvaluationTemplateRepositoryAdapter implements EvaluationTemplateRepository {

    private final JpaEvaluationTemplateRepository jpa;

    @Override
    public Optional<EvaluationTemplate> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<EvaluationTemplate> findAll() {
        return jpa.findAll();
    }

    @Override
    public List<EvaluationTemplate> findAllActive() {
        return jpa.findByActiveTrue();
    }

    @Override
    public EvaluationTemplate save(EvaluationTemplate template) {
        return jpa.save(template);
    }
}
