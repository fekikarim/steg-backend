package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentCalculation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCalculationRepository {
    Optional<PaymentCalculation> findByFinanceCaseId(UUID financeCaseId);
    List<PaymentCalculation> findAllByFinanceCaseIdOrderByCalculationSequenceAsc(UUID financeCaseId);
    /**
     * A14 N+1 fix: bulk-load calculation histories for a whole result page in
     * one query instead of one query per finance case.
     */
    List<PaymentCalculation> findAllByFinanceCaseIdInOrderByCalculationSequenceAsc(Collection<UUID> financeCaseIds);
    PaymentCalculation save(PaymentCalculation calculation);
}
