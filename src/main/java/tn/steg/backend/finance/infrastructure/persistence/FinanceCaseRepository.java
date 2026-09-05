package tn.steg.backend.finance.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinanceCaseRepository extends JpaRepository<FinanceCase, UUID>,
        tn.steg.backend.finance.domain.repository.FinanceCaseRepository {
    Optional<FinanceCase> findByReference(String reference);
    Optional<FinanceCase> findByInternshipId(UUID internshipId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FinanceCase f where f.id = :id")
    Optional<FinanceCase> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    Page<FinanceCase> findByStatus(FinanceCaseStatus status, Pageable pageable);
    boolean existsByReference(String reference);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(f) FROM FinanceCase f WHERE f.reference LIKE :prefix%")
    long countByReferencePrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
