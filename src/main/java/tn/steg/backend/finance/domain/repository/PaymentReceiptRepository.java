package tn.steg.backend.finance.domain.repository;

import tn.steg.backend.finance.domain.model.PaymentReceipt;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentReceiptRepository {
    Optional<PaymentReceipt> findById(UUID id);
    Optional<PaymentReceipt> findByReference(String reference);
    Optional<PaymentReceipt> findByFinanceCaseId(UUID financeCaseId);
    /**
     * A14 N+1 fix: bulk-load receipts for a whole result page in one query
     * instead of one query per finance case.
     */
    List<PaymentReceipt> findByFinanceCaseIdIn(Collection<UUID> financeCaseIds);
    PaymentReceipt save(PaymentReceipt receipt);
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
}
