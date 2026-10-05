package tn.steg.backend.candidate.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID>, tn.steg.backend.candidate.domain.repository.CandidateRepository {
    Optional<Candidate> findByUserId(UUID userId);
    Optional<Candidate> findByNationalIdHash(String nationalIdHash);
    Optional<Candidate> findByNationalIdHashAndDeletedAtIsNull(String nationalIdHash);
    Optional<Candidate> findByEmail(String email);
    List<Candidate> findByEmailIgnoreCase(String email);
    List<Candidate> findByPhone(String phone);
    boolean existsByNationalIdHash(String nationalIdHash);
    boolean existsByNationalIdHashAndDeletedAtIsNull(String nationalIdHash);
    boolean existsByUserIdAndDeletedAtIsNull(UUID userId);
    List<Candidate> findByManagedByIdAndDeletedAtIsNull(UUID managedByUserId);
    List<Candidate> findByIdInAndDeletedAtIsNull(List<UUID> ids);
    boolean existsByIdAndManagedByIdAndDeletedAtIsNull(UUID id, UUID managedByUserId);

    /**
     * Server-side staff queue (AGENTS.md §5.1): pagination, search, filters and
     * sort happen in the database. User and university are fetched in the page
     * query itself (to-one fetch joins — pagination-safe), so rendering a row
     * never triggers extra selects.
     *
     * <p>Join discipline (pre-check (b) rules): {@code c.user} is nullable and
     * always reached through an explicit LEFT JOIN; every multi-hop traversal in
     * the EXISTS subqueries uses explicit joins ({@code JOIN ia.supervisorUser},
     * {@code JOIN i.supervisorUser}) so no implicit INNER JOIN can silently
     * drop rows with a NULL association. Equality predicates on nullable
     * relations ({@code i.candidate = c}) are safe by definition: a NULL row
     * must not match.
     */
    @Query(value = "SELECT c FROM Candidate c "
            + "LEFT JOIN FETCH c.user u "
            + "LEFT JOIN FETCH c.university un "
            + "WHERE c.deletedAt IS NULL "
            + "AND (:scoped = false OR c.id IN :candidateIds) "
            + "AND (:universityName IS NULL OR LOWER(un.name) = :universityName) "
            + "AND (:searchPattern IS NULL "
            + "     OR LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE :searchPattern "
            + "     OR LOWER(c.email) LIKE :searchPattern "
            + "     OR LOWER(un.name) LIKE :searchPattern) "
            + "AND (:accountActive IS NULL "
            + "     OR (:accountActive = true AND u.id IS NOT NULL AND u.enabled = true) "
            + "     OR (:accountActive = false AND (u.id IS NULL OR u.enabled = false))) "
            + "AND (:status IS NULL OR EXISTS (SELECT a FROM InternshipApplication a "
            + "     WHERE a.candidate = c AND a.status = :status)) "
            + "AND (:type IS NULL "
            + "     OR EXISTS (SELECT i FROM Internship i WHERE i.candidate = c AND i.type = :type) "
            + "     OR EXISTS (SELECT a2 FROM InternshipApplication a2 "
            + "         WHERE a2.candidate = c AND a2.calculatedType = :type)) "
            + "AND (:supervisorUserId IS NULL "
            + "     OR c.managedBy.id = :supervisorUserId "
            + "     OR EXISTS (SELECT ia FROM InternshipAssignment ia "
            + "         JOIN ia.supervisorUser sup "
            + "         JOIN ia.internship i2 "
            + "         WHERE sup.id = :supervisorUserId AND ia.status = :assignmentStatus "
            + "         AND i2.candidate = c) "
            + "     OR EXISTS (SELECT i3 FROM Internship i3 "
            + "         JOIN i3.supervisorUser sup2 "
            + "         WHERE sup2.id = :supervisorUserId AND i3.candidate = c)) "
            + "AND (CAST(:createdFrom AS timestamp) IS NULL OR c.createdAt >= :createdFrom) "
            + "AND (CAST(:createdToExclusive AS timestamp) IS NULL OR c.createdAt < :createdToExclusive)",
           countQuery = "SELECT COUNT(c) FROM Candidate c "
            + "LEFT JOIN c.user u "
            + "LEFT JOIN c.university un "
            + "WHERE c.deletedAt IS NULL "
            + "AND (:scoped = false OR c.id IN :candidateIds) "
            + "AND (:universityName IS NULL OR LOWER(un.name) = :universityName) "
            + "AND (:searchPattern IS NULL "
            + "     OR LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE :searchPattern "
            + "     OR LOWER(c.email) LIKE :searchPattern "
            + "     OR LOWER(un.name) LIKE :searchPattern) "
            + "AND (:accountActive IS NULL "
            + "     OR (:accountActive = true AND u.id IS NOT NULL AND u.enabled = true) "
            + "     OR (:accountActive = false AND (u.id IS NULL OR u.enabled = false))) "
            + "AND (:status IS NULL OR EXISTS (SELECT a FROM InternshipApplication a "
            + "     WHERE a.candidate = c AND a.status = :status)) "
            + "AND (:type IS NULL "
            + "     OR EXISTS (SELECT i FROM Internship i WHERE i.candidate = c AND i.type = :type) "
            + "     OR EXISTS (SELECT a2 FROM InternshipApplication a2 "
            + "         WHERE a2.candidate = c AND a2.calculatedType = :type)) "
            + "AND (:supervisorUserId IS NULL "
            + "     OR c.managedBy.id = :supervisorUserId "
            + "     OR EXISTS (SELECT ia FROM InternshipAssignment ia "
            + "         JOIN ia.supervisorUser sup "
            + "         JOIN ia.internship i2 "
            + "         WHERE sup.id = :supervisorUserId AND ia.status = :assignmentStatus "
            + "         AND i2.candidate = c) "
            + "     OR EXISTS (SELECT i3 FROM Internship i3 "
            + "         JOIN i3.supervisorUser sup2 "
            + "         WHERE sup2.id = :supervisorUserId AND i3.candidate = c)) "
            + "AND (CAST(:createdFrom AS timestamp) IS NULL OR c.createdAt >= :createdFrom) "
            + "AND (CAST(:createdToExclusive AS timestamp) IS NULL OR c.createdAt < :createdToExclusive)")
    Page<Candidate> searchStaffCandidates(
            @Param("scoped") boolean scoped,
            @Param("candidateIds") List<UUID> candidateIds,
            @Param("universityName") String universityName,
            @Param("searchPattern") String searchPattern,
            @Param("accountActive") Boolean accountActive,
            @Param("status") ApplicationStatus status,
            @Param("type") InternshipType type,
            @Param("supervisorUserId") UUID supervisorUserId,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdToExclusive") Instant createdToExclusive,
            Pageable pageable);
}
