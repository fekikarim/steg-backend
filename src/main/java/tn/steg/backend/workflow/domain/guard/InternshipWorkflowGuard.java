package tn.steg.backend.workflow.domain.guard;

import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.InternshipWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

/**
 * Guard for the Internship Execution Lifecycle workflow process.
 * Governs transitions across PLANNED, ACTIVE, and COMPLETED.
 */
public class InternshipWorkflowGuard implements WorkflowTransitionGuard<InternshipWorkflowInstance> {

    @Override
    public boolean supports(WorkflowInstance instance) {
        return instance instanceof InternshipWorkflowInstance;
    }

    @Override
    public void validateTransition(InternshipWorkflowInstance instance, String targetStepCode,
                                   WorkflowActionType actionType, ApprovalDecision decision) {
        Internship internship = instance.getInternship();
        if (internship == null) {
            throw new BusinessRuleException("WORKFLOW_INTERNSHIP_MISSING", "Workflow instance is not linked to an internship.");
        }

        InternshipStatus currentStatus = internship.getStatus();

        if ("ACTIVE".equalsIgnoreCase(targetStepCode)) {
            if (currentStatus != InternshipStatus.PLANNED) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot activate an internship that is not PLANNED. Current: " + currentStatus);
            }
        } else if ("COMPLETED".equalsIgnoreCase(targetStepCode)) {
            if (currentStatus != InternshipStatus.ACTIVE) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot complete an internship that is not ACTIVE. Current: " + currentStatus);
            }
        } else {
            throw new BusinessRuleException("UNKNOWN_WORKFLOW_STEP", "Unknown step code: " + targetStepCode);
        }
    }
}
