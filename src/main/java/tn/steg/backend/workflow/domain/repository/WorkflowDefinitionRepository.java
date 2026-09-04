package tn.steg.backend.workflow.domain.repository;

import tn.steg.backend.workflow.domain.model.WorkflowDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository {
    List<WorkflowDefinition> findAll();
    Optional<WorkflowDefinition> findById(UUID id);
    Optional<WorkflowDefinition> findByCode(String code);
    WorkflowDefinition save(WorkflowDefinition definition);
}
