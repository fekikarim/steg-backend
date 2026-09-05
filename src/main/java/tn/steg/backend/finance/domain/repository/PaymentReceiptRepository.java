package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentReceipt;

import java.util.Optional;
import java.util.UUID;

public interface PaymentReceiptRepository {
    Optional<PaymentReceipt> findById(UUID id);
    Optional<PaymentReceipt> findByReference(String reference);
    Optional<PaymentReceipt> findByFinanceCaseId(UUID financeCaseId);
    PaymentReceipt save(PaymentReceipt receipt);
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
}
