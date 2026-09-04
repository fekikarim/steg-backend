package tn.steg.backend.workflow.application.dto;

import tn.steg.backend.workflow.domain.model.WorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only DTO representing a workflow instance summary.
 */
public record WorkflowInstanceResponse(
        UUID id,
        String definitionCode,
        String definitionName,
        String currentStepCode,
        String currentStepName,
        WorkflowStatus status,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt
) {
    public static WorkflowInstanceResponse from(WorkflowInstance instance) {
        return new WorkflowInstanceResponse(
                instance.getId(),
                instance.getDefinition().getCode(),
                instance.getDefinition().getName(),
                instance.getCurrentStep() != null ? instance.getCurrentStep().getCode() : null,
                instance.getCurrentStep() != null ? instance.getCurrentStep().getName() : null,
                instance.getStatus(),
                instance.getStartedAt(),
                instance.getCompletedAt(),
                instance.getCancelledAt()
        );
    }
}
