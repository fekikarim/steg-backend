package tn.steg.backend.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.FinanceCase;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinanceCaseRepository extends JpaRepository<FinanceCase, UUID> {
    Optional<FinanceCase> findByReference(String reference);
    Optional<FinanceCase> findByInternshipId(UUID internshipId);
}
