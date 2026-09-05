package tn.steg.backend.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.PaymentCalculation;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentCalculationRepository extends JpaRepository<PaymentCalculation, UUID>,
        tn.steg.backend.finance.domain.repository.PaymentCalculationRepository {
    Optional<PaymentCalculation> findByFinanceCaseId(UUID financeCaseId);
}
