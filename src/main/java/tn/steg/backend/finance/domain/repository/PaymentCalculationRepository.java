package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentCalculation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCalculationRepository {
    Optional<PaymentCalculation> findByFinanceCaseId(UUID financeCaseId);
    List<PaymentCalculation> findAllByFinanceCaseIdOrderByCalculationSequenceAsc(UUID financeCaseId);
    PaymentCalculation save(PaymentCalculation calculation);
}
