package tn.steg.backend.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.PaymentApproval;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentApprovalRepository extends JpaRepository<PaymentApproval, UUID>,
        tn.steg.backend.finance.domain.repository.PaymentApprovalRepository {
    List<PaymentApproval> findByFinanceCaseIdOrderByDecisionSequenceAsc(UUID financeCaseId);
    long countByFinanceCaseId(UUID financeCaseId);
}
