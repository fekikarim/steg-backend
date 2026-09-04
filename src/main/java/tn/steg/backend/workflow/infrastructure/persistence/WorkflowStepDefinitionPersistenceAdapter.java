package tn.steg.backend.workflow.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.workflow.domain.model.WorkflowStepDefinition;
import tn.steg.backend.workflow.domain.repository.WorkflowStepDefinitionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter that wraps the JPA repo to satisfy the domain port.
 */
@Component
@RequiredArgsConstructor
public class WorkflowStepDefinitionPersistenceAdapter implements WorkflowStepDefinitionRepository {

    private final WorkflowStepDefinitionJpaRepository jpaRepository;

    @Override
    public List<WorkflowStepDefinition> findByDefinitionIdOrderBySequenceNumAsc(UUID definitionId) {
        return jpaRepository.findByDefinitionIdOrderBySequenceNumAsc(definitionId);
    }

    @Override
    public Optional<WorkflowStepDefinition> findByDefinitionIdAndCode(UUID definitionId, String code) {
        return jpaRepository.findByDefinitionIdAndCode(definitionId, code);
    }

    @Override
    public WorkflowStepDefinition save(WorkflowStepDefinition stepDefinition) {
        return jpaRepository.save(stepDefinition);
    }
}
