package tn.steg.backend.workflow.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.workflow.domain.model.WorkflowAction;
import tn.steg.backend.workflow.domain.repository.WorkflowActionRepository;

import java.util.List;
import java.util.UUID;

/**
 * Adapter that wraps the JPA repo to satisfy the domain WorkflowActionRepository port.
 */
@Component
@RequiredArgsConstructor
public class WorkflowActionPersistenceAdapter implements WorkflowActionRepository {

    private final WorkflowActionJpaRepository jpaRepository;

    @Override
    public List<WorkflowAction> findByInstanceIdOrderBySequenceNumberAsc(UUID instanceId) {
        return jpaRepository.findByInstanceIdOrderBySequenceNumberAsc(instanceId);
    }

    @Override
    public long countByInstanceId(UUID instanceId) {
        return jpaRepository.countByInstanceId(instanceId);
    }

    @Override
    public WorkflowAction save(WorkflowAction action) {
        return jpaRepository.save(action);
    }

    @Override
    public WorkflowAction saveAndFlush(WorkflowAction action) {
        return jpaRepository.saveAndFlush(action);
    }
}
