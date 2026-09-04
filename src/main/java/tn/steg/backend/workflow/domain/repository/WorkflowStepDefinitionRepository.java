package tn.steg.backend.workflow.domain.repository;

import tn.steg.backend.workflow.domain.model.WorkflowStepDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStepDefinitionRepository {
    List<WorkflowStepDefinition> findByDefinitionIdOrderBySequenceNumAsc(UUID definitionId);
    Optional<WorkflowStepDefinition> findByDefinitionIdAndCode(UUID definitionId, String code);
    WorkflowStepDefinition save(WorkflowStepDefinition stepDefinition);
}
