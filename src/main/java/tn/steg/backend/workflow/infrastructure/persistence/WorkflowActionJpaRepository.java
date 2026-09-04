package tn.steg.backend.workflow.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.workflow.domain.model.WorkflowAction;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for WorkflowAction.
 */
@Repository
public interface WorkflowActionJpaRepository extends JpaRepository<WorkflowAction, UUID> {

    List<WorkflowAction> findByInstanceIdOrderBySequenceNumberAsc(UUID instanceId);

    @Query("SELECT COUNT(wa) FROM WorkflowAction wa WHERE wa.instance.id = :instanceId")
    long countByInstanceId(@Param("instanceId") UUID instanceId);
}
