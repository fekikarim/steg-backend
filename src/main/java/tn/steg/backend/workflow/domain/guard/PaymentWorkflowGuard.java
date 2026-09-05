package tn.steg.backend.workflow.domain.guard;

import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.PaymentWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

/**
 * Guard for the Payment Approval workflow process.
 * Governs transitions across CASE_OPENED, FINANCE_VERIFICATION, and PAYMENT_APPROVED.
 */
public class PaymentWorkflowGuard implements WorkflowTransitionGuard<PaymentWorkflowInstance> {

    @Override
    public boolean supports(WorkflowInstance instance) {
        return instance instanceof PaymentWorkflowInstance;
    }

    @Override
    public void validateTransition(PaymentWorkflowInstance instance, String targetStepCode,
                                   WorkflowActionType actionType, ApprovalDecision decision) {
        FinanceCase financeCase = instance.getFinanceCase();
        if (financeCase == null) {
            throw new BusinessRuleException("WORKFLOW_FINANCE_CASE_MISSING", "Workflow instance is not linked to a finance case.");
        }

        FinanceCaseStatus currentStatus = financeCase.getStatus();

        if ("FINANCE_VERIFICATION".equalsIgnoreCase(targetStepCode)) {
            if (currentStatus != FinanceCaseStatus.OPENED) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot move to verification unless finance case is OPENED. Current: " + currentStatus);
            }
        } else if ("PAYMENT_APPROVED".equalsIgnoreCase(targetStepCode)) {
            // Phase A11: the FinanceService only requests approval from
            // READY_FOR_DECISION, but the guard stays permissive for any
            // non-terminal state and strictly forbids decided/closed cases.
            if (currentStatus == FinanceCaseStatus.APPROVED
                    || currentStatus == FinanceCaseStatus.REJECTED
                    || currentStatus == FinanceCaseStatus.CLOSED) {
                throw new BusinessRuleException("ILLEGAL_WORKFLOW_TRANSITION",
                        "Cannot decide a payment for a case that is already decided or closed. Current: " + currentStatus);
            }
            if (decision != ApprovalDecision.APPROVED && decision != ApprovalDecision.REJECTED) {
                throw new BusinessRuleException("INVALID_DECISION", "Payment approval requires decision APPROVED or REJECTED.");
            }
        } else {
            throw new BusinessRuleException("UNKNOWN_WORKFLOW_STEP", "Unknown step code: " + targetStepCode);
        }
    }
}
