package tn.steg.backend.workflow.domain.guard;

import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

/**
 * Guard for the Application Review workflow process.
 * Governs transitions across SUBMITTED, UNDER_REVIEW, and FINAL_DECISION.
 */
public class ApplicationWorkflowGuard implements WorkflowTransitionGuard<ApplicationWorkflowInstance> {

    @Override
    public boolean supports(WorkflowInstance instance) {
        return instance instanceof ApplicationWorkflowInstance;
    }

    @Override
    public void validateTransition(ApplicationWorkflowInstance instance, String targetStepCode,
                                   WorkflowActionType actionType, ApprovalDecision decision) {
        InternshipApplication application = instance.getApplication();
        if (application == null) {
            throw new BusinessRuleException("WORKFLOW_APPLICATION_MISSING", "Workflow instance is not linked to an application.");
        }

        ApplicationStatus currentAppStatus = application.getStatus();

        // Check target step
        if ("UNDER_REVIEW".equalsIgnoreCase(targetStepCode)) {
            if (currentAppStatus != ApplicationStatus.SUBMITTED && currentAppStatus != ApplicationStatus.NEEDS_CORRECTION) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot transition to UNDER_REVIEW from application status " + currentAppStatus);
            }
        } else if ("FINAL_DECISION".equalsIgnoreCase(targetStepCode)) {
            if (currentAppStatus != ApplicationStatus.UNDER_REVIEW) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot execute FINAL_DECISION unless application is UNDER_REVIEW. Current: " + currentAppStatus);
            }
            if (decision != ApprovalDecision.APPROVED && decision != ApprovalDecision.REJECTED && decision != ApprovalDecision.NEEDS_CORRECTION) {
                throw new BusinessRuleException("INVALID_DECISION",
                        "FINAL_DECISION requires decision APPROVED, REJECTED, or NEEDS_CORRECTION.");
            }
        } else {
            throw new BusinessRuleException("UNKNOWN_WORKFLOW_STEP", "Unknown step code: " + targetStepCode);
        }
    }
}
