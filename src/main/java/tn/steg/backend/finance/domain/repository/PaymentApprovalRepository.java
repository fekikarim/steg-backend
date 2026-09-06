package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentApproval;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentApprovalRepository {
    List<PaymentApproval> findByFinanceCaseIdOrderByDecisionSequenceAsc(UUID financeCaseId);
    /**
     * A14 N+1 fix: bulk-load approval histories for a whole result page in one
     * query instead of one query per finance case. The deciding employee is
     * fetch-joined by the infrastructure adapter (it is rendered per approval).
     */
    List<PaymentApproval> findByFinanceCaseIdInWithDecider(Collection<UUID> financeCaseIds);
    PaymentApproval save(PaymentApproval approval);
    long countByFinanceCaseId(UUID financeCaseId);
}
