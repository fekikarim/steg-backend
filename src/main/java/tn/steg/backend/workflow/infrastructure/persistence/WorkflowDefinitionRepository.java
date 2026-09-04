package tn.steg.backend.workflow.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.workflow.domain.model.WorkflowDefinition;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID>, tn.steg.backend.workflow.domain.repository.WorkflowDefinitionRepository {
    Optional<WorkflowDefinition> findByCode(String code);
}
