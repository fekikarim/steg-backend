package tn.steg.backend.workflow.domain.guard;

import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.InvalidStateTransitionException;
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

        // Check target step. AGENTS.md §4 review matrix: a candidate submission
        // (SUBMITTED) and a post-correction resubmission (RESUBMITTED) are both
        // reviewable; MODIFICATION_REQUESTED waits for the candidate to
        // resubmit first, so staff may not skip that wait.
        if ("UNDER_REVIEW".equalsIgnoreCase(targetStepCode)) {
            if (currentAppStatus != ApplicationStatus.SUBMITTED
                    && currentAppStatus != ApplicationStatus.RESUBMITTED) {
                throw new InvalidStateTransitionException(
                        currentAppStatus.name(), "UNDER_REVIEW",
                        "Cannot transition to UNDER_REVIEW from application status " + currentAppStatus);
            }
        } else if ("FINAL_DECISION".equalsIgnoreCase(targetStepCode)) {
            // The Admin may decide directly on a fresh submission or a
            // resubmission (Scenario A approves right after submission);
            // UNDER_REVIEW stays a valid source for two-step reviews.
            if (currentAppStatus != ApplicationStatus.SUBMITTED
                    && currentAppStatus != ApplicationStatus.RESUBMITTED
                    && currentAppStatus != ApplicationStatus.UNDER_REVIEW) {
                throw new InvalidStateTransitionException(
                        currentAppStatus.name(), "FINAL_DECISION",
                        "Cannot execute FINAL_DECISION from application status " + currentAppStatus
                                + ". Expected SUBMITTED, RESUBMITTED or UNDER_REVIEW.");
            }
            if (decision != ApprovalDecision.APPROVED && decision != ApprovalDecision.REJECTED && decision != ApprovalDecision.MODIFICATION_REQUESTED) {
                throw new BusinessRuleException("INVALID_DECISION",
                        "FINAL_DECISION requires decision APPROVED, REJECTED, or MODIFICATION_REQUESTED.");
            }
        } else {
            throw new BusinessRuleException("UNKNOWN_WORKFLOW_STEP", "Unknown step code: " + targetStepCode);
        }
    }
}
