package tn.steg.backend.workflow.application.dto;

import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowAction;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only DTO representing a recorded workflow action.
 */
public record WorkflowActionResponse(
        UUID id,
        UUID instanceId,
        String stepCode,
        String stepName,
        UUID performedById,
        String performedByUsername,
        WorkflowActionType type,
        ApprovalDecision decision,
        String comment,
        Long sequenceNumber,
        Instant performedAt
) {
    public static WorkflowActionResponse from(WorkflowAction action) {
        return new WorkflowActionResponse(
                action.getId(),
                action.getInstance().getId(),
                action.getStep().getCode(),
                action.getStep().getName(),
                action.getPerformedBy().getId(),
                action.getPerformedBy().getEmail(),
                action.getType(),
                action.getDecision(),
                action.getComment(),
                action.getSequenceNumber(),
                action.getPerformedAt()
        );
    }
}
