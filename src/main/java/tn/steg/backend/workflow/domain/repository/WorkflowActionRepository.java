package tn.steg.backend.workflow.domain.repository;

import tn.steg.backend.workflow.domain.model.WorkflowAction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowActionRepository {
    List<WorkflowAction> findByInstanceIdOrderBySequenceNumberAsc(UUID instanceId);
    long countByInstanceId(UUID instanceId);
    WorkflowAction save(WorkflowAction action);
    WorkflowAction saveAndFlush(WorkflowAction action);
}
