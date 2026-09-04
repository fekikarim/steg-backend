package tn.steg.backend.workflow.domain.guard;

import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

/**
 * Domain guard interface responsible for enforcing legal transitions
 * on a specific workflow instance type.
 */
public interface WorkflowTransitionGuard<T extends WorkflowInstance> {

    /**
     * Checks if this guard supports the given instance type.
     */
    boolean supports(WorkflowInstance instance);

    /**
     * Validates whether the requested action and decision is legal
     * given the current state of the workflow instance and its target aggregate.
     * Throws BusinessRuleException if the transition is illegal.
     */
    void validateTransition(T instance, String targetStepCode, WorkflowActionType actionType, ApprovalDecision decision);
}
