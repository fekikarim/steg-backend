package tn.steg.backend.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.PaymentReceipt;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, UUID>,
        tn.steg.backend.finance.domain.repository.PaymentReceiptRepository {
    Optional<PaymentReceipt> findByReference(String reference);
    Optional<PaymentReceipt> findByFinanceCaseId(UUID financeCaseId);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT nextval('payment_receipt_reference_seq')", nativeQuery = true)
    long nextReferenceSequence();
}
