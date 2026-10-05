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

    /**
     * Supervisor scope for the finance queue.
     *
     * <p>The legacy employee link is an explicit LEFT JOIN on purpose: an
     * implicit join path ({@code a.supervisor.user.id}) compiles to an INNER
     * JOIN, so every user-backed assignment (legacy {@code supervisor_id} NULL
     * since V36) was silently filtered out and the supervisor saw an empty
     * queue. Same defect as
     * {@code InternshipAssignmentRepository.findBySupervisorUserIdAndStatus}.
     */
    @Query(value = "SELECT fc FROM FinanceCase fc LEFT JOIN FETCH fc.internship i "
                 + "JOIN InternshipAssignment a ON a.internship.id = i.id "
                 + "LEFT JOIN a.supervisor legacySupervisor "
                 + "WHERE (a.supervisorUser.id = :supervisorUserId OR legacySupervisor.user.id = :supervisorUserId) "
                 + "AND a.status = tn.steg.backend.internship.domain.model.AssignmentStatus.ACTIVE",
           countQuery = "SELECT COUNT(fc) FROM FinanceCase fc JOIN fc.internship i "
                 + "JOIN InternshipAssignment a ON a.internship.id = i.id "
                 + "LEFT JOIN a.supervisor legacySupervisor "
                 + "WHERE (a.supervisorUser.id = :supervisorUserId OR legacySupervisor.user.id = :supervisorUserId) "
                 + "AND a.status = tn.steg.backend.internship.domain.model.AssignmentStatus.ACTIVE")
    Page<FinanceCase> findAllForSupervisor(@Param("supervisorUserId") UUID supervisorUserId, Pageable pageable);

    @Query(value = "SELECT fc FROM FinanceCase fc LEFT JOIN FETCH fc.internship i "
                 + "JOIN InternshipAssignment a ON a.internship.id = i.id "
                 + "LEFT JOIN a.supervisor legacySupervisor "
                 + "WHERE fc.status = :status "
                 + "AND (a.supervisorUser.id = :supervisorUserId OR legacySupervisor.user.id = :supervisorUserId) "
                 + "AND a.status = tn.steg.backend.internship.domain.model.AssignmentStatus.ACTIVE",
           countQuery = "SELECT COUNT(fc) FROM FinanceCase fc JOIN fc.internship i "
                 + "JOIN InternshipAssignment a ON a.internship.id = i.id "
                 + "LEFT JOIN a.supervisor legacySupervisor "
                 + "WHERE fc.status = :status "
                 + "AND (a.supervisorUser.id = :supervisorUserId OR legacySupervisor.user.id = :supervisorUserId) "
                 + "AND a.status = tn.steg.backend.internship.domain.model.AssignmentStatus.ACTIVE")
    Page<FinanceCase> findByStatusForSupervisor(@Param("status") FinanceCaseStatus status,
            @Param("supervisorUserId") UUID supervisorUserId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT nextval('finance_case_reference_seq')", nativeQuery = true)
    long nextReferenceSequence();
}
