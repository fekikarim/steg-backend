package tn.steg.backend.finance.application.dto;

import tn.steg.backend.finance.domain.model.PaymentApproval;
import tn.steg.backend.finance.domain.model.PaymentApprovalDecision;

import java.time.Instant;
import java.util.UUID;

public record PaymentApprovalResponse(
        UUID id,
        PaymentApprovalDecision decision,
        String comment,
        int decisionSequence,
        Instant decidedAt,
        String decidedByName
) {
    public static PaymentApprovalResponse from(PaymentApproval approval) {
        String name = approval.getDecidedBy() == null ? null
                : approval.getDecidedBy().getFirstName() + " " + approval.getDecidedBy().getLastName();
        return new PaymentApprovalResponse(
                approval.getId(),
                approval.getDecision(),
                approval.getComment(),
                approval.getDecisionSequence(),
                approval.getDecidedAt(),
                name);
    }
}
