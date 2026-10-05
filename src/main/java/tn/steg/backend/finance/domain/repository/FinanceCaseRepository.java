package tn.steg.backend.finance.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;

import java.util.Optional;
import java.util.UUID;

public interface FinanceCaseRepository {
    Optional<FinanceCase> findById(UUID id);

    /**
     * Pessimistic write lock for decision paths (approve/reject/recalculate):
     * concurrent attempts on the same case serialize, so exactly one wins and
     * the losers observe the terminal state instead of racing past the guards.
     */
    Optional<FinanceCase> findByIdForUpdate(UUID id);
    Optional<FinanceCase> findByReference(String reference);
    Optional<FinanceCase> findByInternshipId(UUID internshipId);
    Page<FinanceCase> findAll(Pageable pageable);
    Page<FinanceCase> findByStatus(FinanceCaseStatus status, Pageable pageable);
    /**
     * A14 N+1 fix: list-view variants fetching the linked internship eagerly.
     * Single-valued fetch joins are pagination-safe (no row multiplication).
     * The JPQL lives on the infrastructure adapter.
     */
    Page<FinanceCase> findAllWithInternship(Pageable pageable);
    Page<FinanceCase> findByStatusWithInternship(FinanceCaseStatus status, Pageable pageable);
    /**
     * Supervisor-scoped list views: only cases for internships with an ACTIVE
     * assignment to the supervisor. Join (not fetch) on assignments keeps the
     * page query pagination-safe; internship rendering reuses the A14 batching.
     */
    Page<FinanceCase> findAllForSupervisor(UUID supervisorUserId, Pageable pageable);
    Page<FinanceCase> findByStatusForSupervisor(FinanceCaseStatus status, UUID supervisorUserId, Pageable pageable);
    FinanceCase save(FinanceCase financeCase);
    /** Atomic reference counter (sequence-backed, race-free — see V51). */
    long nextReferenceSequence();
}
