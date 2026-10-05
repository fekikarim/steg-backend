package tn.steg.backend.internship.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.Internship;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tn.steg.backend.internship.domain.model.InternshipStatus;

@Repository
public interface InternshipRepository extends JpaRepository<Internship, UUID>, tn.steg.backend.internship.domain.repository.InternshipRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Internship i where i.id = :id")
    Optional<Internship> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);

    @Query("SELECT COUNT(i) FROM Internship i WHERE i.reference LIKE :prefix%")
    long countByReferencePrefix(@Param("prefix") String prefix);

    @Query("select i from Internship i where i.candidate.id = :candidateId")
    List<Internship> findByCandidateId(@Param("candidateId") UUID candidateId);

    /**
     * A14 N+1 fix: one query for the whole staff list, candidate + application
     * fetched eagerly (both are rendered by {@code InternshipResponse}).
     */
    @Query("SELECT DISTINCT i FROM Internship i " +
           "LEFT JOIN FETCH i.candidate LEFT JOIN FETCH i.application")
    List<Internship> findAllWithDetails();

    @Query("select i from Internship i where i.candidate.user.id = :userId and i.status = :status")
    List<Internship> findByCandidateUserIdAndStatus(@Param("userId") UUID userId, @Param("status") InternshipStatus status);

    @Query("select i from Internship i where i.supervisorUser.id = :supervisorUserId")
    List<Internship> findBySupervisorUserId(@Param("supervisorUserId") UUID supervisorUserId);

    /**
     * S7 validation queue (§5.11 step 1): Admin-only, one paged query with
     * explicit JOINs (NULL supervisor/university rows are kept) and the
     * filters mirrored in the count query. Sort/whitelist + size clamp live in
     * the application service, like the other staff queues.
     */
    @Query(value = "SELECT DISTINCT i FROM Internship i "
            + "LEFT JOIN FETCH i.candidate c LEFT JOIN FETCH c.university u LEFT JOIN FETCH i.supervisorUser s "
            + "WHERE (:statusesEmpty = TRUE OR i.status IN :statuses) "
            + "AND (:supervisorUserId IS NULL OR s.id = :supervisorUserId) "
            + "AND (:universityId IS NULL OR u.id = :universityId) "
            + "AND (:type IS NULL OR i.type = :type) "
            + "AND (:pattern IS NULL OR LOWER(c.firstName) LIKE :pattern OR LOWER(c.lastName) LIKE :pattern "
            + "OR LOWER(c.email) LIKE :pattern OR LOWER(i.reference) LIKE :pattern)",
            countQuery = "SELECT COUNT(DISTINCT i) FROM Internship i "
                    + "LEFT JOIN i.candidate c LEFT JOIN c.university u LEFT JOIN i.supervisorUser s "
                    + "WHERE (:statusesEmpty = TRUE OR i.status IN :statuses) "
                    + "AND (:supervisorUserId IS NULL OR s.id = :supervisorUserId) "
                    + "AND (:universityId IS NULL OR u.id = :universityId) "
                    + "AND (:type IS NULL OR i.type = :type) "
                    + "AND (:pattern IS NULL OR LOWER(c.firstName) LIKE :pattern OR LOWER(c.lastName) LIKE :pattern "
                    + "OR LOWER(c.email) LIKE :pattern OR LOWER(i.reference) LIKE :pattern)")
    org.springframework.data.domain.Page<Internship> searchValidationQueue(
            @Param("statusesEmpty") boolean statusesEmpty,
            @Param("statuses") java.util.Collection<InternshipStatus> statuses,
            @Param("supervisorUserId") UUID supervisorUserId,
            @Param("universityId") UUID universityId,
            @Param("type") tn.steg.backend.internship.domain.model.InternshipType type,
            @Param("pattern") String pattern,
            org.springframework.data.domain.Pageable pageable);
}
