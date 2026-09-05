package tn.steg.backend.finance.application.dto;

import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FinanceCaseResponse(
        UUID id,
        String reference,
        FinanceCaseStatus status,
        UUID internshipId,
        String internshipReference,
        Instant openedAt,
        Instant closedAt,
        UUID workflowInstanceId,
        PaymentCalculationResponse calculation,
        List<FinanceCaseDocumentResponse> documents,
        List<PaymentApprovalResponse> approvals,
        String receiptReference
) {
    public static FinanceCaseResponse from(FinanceCase financeCase,
                                           PaymentCalculationResponse calculation,
                                           List<FinanceCaseDocumentResponse> documents,
                                           List<PaymentApprovalResponse> approvals,
                                           String receiptReference,
                                           UUID workflowInstanceId) {
        return new FinanceCaseResponse(
                financeCase.getId(),
                financeCase.getReference(),
                financeCase.getStatus(),
                financeCase.getInternship().getId(),
                financeCase.getInternship().getReference(),
                financeCase.getOpenedAt(),
                financeCase.getClosedAt(),
                workflowInstanceId,
                calculation,
                documents,
                approvals,
                receiptReference);
    }
}
