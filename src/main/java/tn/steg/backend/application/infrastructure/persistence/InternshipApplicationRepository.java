package tn.steg.backend.application.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipApplicationRepository extends JpaRepository<InternshipApplication, UUID>, tn.steg.backend.application.domain.repository.InternshipApplicationRepository {

    Optional<InternshipApplication> findByReference(String reference);

    boolean existsByReference(String reference);

    /** All applications belonging to a specific candidate. */
    List<InternshipApplication> findByCandidateId(UUID candidateId);

    /**
     * A14 N+1 fix: one query for the whole staff list, candidate + reviewer
     * fetched eagerly (both are rendered by {@code ApplicationResponse}).
     */
    @Query("SELECT DISTINCT a FROM InternshipApplication a " +
           "LEFT JOIN FETCH a.candidate LEFT JOIN FETCH a.reviewer")
    List<InternshipApplication> findAllWithCandidate();

    /**
     * Find an application by ID and verify it belongs to the given user (IDOR guard).
     * The join traverses application → candidate → user.
     */
    @Query("SELECT a FROM InternshipApplication a " +
           "WHERE a.id = :id AND a.candidate.user.id = :userId")
    Optional<InternshipApplication> findByIdAndCandidateUserId(
            @Param("id") UUID id,
            @Param("userId") UUID userId);

    boolean existsByCandidateIdAndStatusNot(UUID candidateId, tn.steg.backend.application.domain.model.ApplicationStatus status);

    /**
     * Count existing applications whose reference starts with the year prefix
     * (e.g. "APP-2025-") to derive the next sequence number.
     */
    @Query("SELECT COUNT(a) FROM InternshipApplication a " +
           "WHERE a.reference LIKE :prefix%")
    long countByReferencePrefix(@Param("prefix") String prefix);

    /**
     * Server-side staff queue (AGENTS.md §5.2): pagination, search, filters and
     * sort happen in the database. Candidate and university are fetched in the
     * page query itself, so rendering a row never triggers extra selects.
     */
    @Query(value = "SELECT a FROM InternshipApplication a "
            + "LEFT JOIN FETCH a.candidate c "
            + "LEFT JOIN FETCH c.university "
            + "WHERE (:scoped = false OR c.id IN :candidateIds) "
            + "AND (:status IS NULL OR a.status = :status) "
            + "AND (:type IS NULL OR a.calculatedType = :type) "
            + "AND (:universityName IS NULL OR LOWER(c.university.name) = :universityName) "
            + "AND (CAST(:submissionFrom AS date) IS NULL OR a.submissionDate >= :submissionFrom) "
            + "AND (CAST(:submissionTo AS date) IS NULL OR a.submissionDate <= :submissionTo) "
            + "AND (:searchPattern IS NULL OR LOWER(a.reference) LIKE :searchPattern "
            + "     OR LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE :searchPattern "
            + "     OR LOWER(c.email) LIKE :searchPattern)",
           countQuery = "SELECT COUNT(a) FROM InternshipApplication a "
            + "LEFT JOIN a.candidate c "
            + "WHERE (:scoped = false OR c.id IN :candidateIds) "
            + "AND (:status IS NULL OR a.status = :status) "
            + "AND (:type IS NULL OR a.calculatedType = :type) "
            + "AND (:universityName IS NULL OR LOWER(c.university.name) = :universityName) "
            + "AND (CAST(:submissionFrom AS date) IS NULL OR a.submissionDate >= :submissionFrom) "
            + "AND (CAST(:submissionTo AS date) IS NULL OR a.submissionDate <= :submissionTo) "
            + "AND (:searchPattern IS NULL OR LOWER(a.reference) LIKE :searchPattern "
            + "     OR LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE :searchPattern "
            + "     OR LOWER(c.email) LIKE :searchPattern)")
    Page<InternshipApplication> searchStaffApplications(
            @Param("scoped") boolean scoped,
            @Param("candidateIds") List<UUID> candidateIds,
            @Param("status") ApplicationStatus status,
            @Param("type") InternshipType type,
            @Param("universityName") String universityName,
            @Param("submissionFrom") LocalDate submissionFrom,
            @Param("submissionTo") LocalDate submissionTo,
            @Param("searchPattern") String searchPattern,
            Pageable pageable);
}
