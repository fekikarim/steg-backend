package tn.steg.backend.workflow.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.workflow.domain.model.WorkflowStepDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for WorkflowStepDefinition.
 * Implements the domain port via the WorkflowStepDefinitionPersistenceAdapter.
 */
@Repository
public interface WorkflowStepDefinitionJpaRepository extends JpaRepository<WorkflowStepDefinition, UUID> {

    List<WorkflowStepDefinition> findByDefinitionIdOrderBySequenceNumAsc(UUID definitionId);

    Optional<WorkflowStepDefinition> findByDefinitionIdAndCode(UUID definitionId, String code);
}
