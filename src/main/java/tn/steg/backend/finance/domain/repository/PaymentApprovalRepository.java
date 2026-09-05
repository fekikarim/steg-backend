package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentApproval;

import java.util.List;
import java.util.UUID;

public interface PaymentApprovalRepository {
    List<PaymentApproval> findByFinanceCaseIdOrderByDecisionSequenceAsc(UUID financeCaseId);
    PaymentApproval save(PaymentApproval approval);
    long countByFinanceCaseId(UUID financeCaseId);
}
