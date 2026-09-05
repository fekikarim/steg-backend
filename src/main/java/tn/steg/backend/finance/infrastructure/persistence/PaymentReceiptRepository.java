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
    boolean existsByReference(String reference);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(r) FROM PaymentReceipt r WHERE r.reference LIKE :prefix%")
    long countByReferencePrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
