package tn.steg.backend.finance.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * A14 N+1 fix backing the domain port methods: the internship is rendered
     * per row, so it is fetch-joined here (single-valued join is safe with
     * pagination — the count query stays separate).
     */
    @Query(value = "SELECT fc FROM FinanceCase fc LEFT JOIN FETCH fc.internship",
           countQuery = "SELECT COUNT(fc) FROM FinanceCase fc")
    Page<FinanceCase> findAllWithInternship(Pageable pageable);

    @Query(value = "SELECT fc FROM FinanceCase fc LEFT JOIN FETCH fc.internship WHERE fc.status = :status",
           countQuery = "SELECT COUNT(fc) FROM FinanceCase fc WHERE fc.status = :status")
    Page<FinanceCase> findByStatusWithInternship(@Param("status") FinanceCaseStatus status, Pageable pageable);
    boolean existsByReference(String reference);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(f) FROM FinanceCase f WHERE f.reference LIKE :prefix%")
    long countByReferencePrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
