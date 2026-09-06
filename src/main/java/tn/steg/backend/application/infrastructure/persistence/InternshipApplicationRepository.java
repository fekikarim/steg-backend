package tn.steg.backend.application.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.InternshipApplication;

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

    /**
     * Count existing applications whose reference starts with the year prefix
     * (e.g. "APP-2025-") to derive the next sequence number.
     */
    @Query("SELECT COUNT(a) FROM InternshipApplication a " +
           "WHERE a.reference LIKE :prefix%")
    long countByReferencePrefix(@Param("prefix") String prefix);
}
