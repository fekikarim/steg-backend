package tn.steg.backend.workflow.application.dto;

import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

/**
 * Request to execute a workflow transition action.
 *
 * @param targetStepCode The code of the step to transition into (e.g. "UNDER_REVIEW", "ACTIVE").
 * @param actionType     The type of action being performed (APPROVAL, VALIDATION, etc.)
 * @param decision       Optional decision (APPROVED, REJECTED, NEEDS_CORRECTION) for approval steps.
 * @param comment        Optional free-text rationale or correction comment.
 */
public record WorkflowTransitionRequest(
        String targetStepCode,
        WorkflowActionType actionType,
        ApprovalDecision decision,
        String comment
) {
}
