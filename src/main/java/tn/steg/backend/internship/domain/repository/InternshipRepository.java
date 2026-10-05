package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipRepository {
    List<Internship> findAll();
    /**
     * A14 N+1 fix: staff list views render candidate name + application id per
     * row. Fetch both associations in the single list query.
     */
    List<Internship> findAllWithDetails();
    Optional<Internship> findById(UUID id);

    /**
     * Pessimistic write lock for check-then-insert flows scoped to one
     * internship (e.g. certificate generation), so concurrent requests
     * serialize instead of racing past existence checks.
     */
    Optional<Internship> findByIdForUpdate(UUID id);
    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
    Internship save(Internship internship);
    List<Internship> findByCandidateId(UUID candidateId);
    /** S5 candidate queue: one query for the internships (type, supervisor) of a whole page. */
    List<Internship> findByCandidateIdIn(java.util.Collection<UUID> candidateIds);
    List<Internship> findByCandidateUserIdAndStatus(UUID userId, InternshipStatus status);
    List<Internship> findBySupervisorUserId(UUID supervisorUserId);
    /** Bounded status sweeps (e.g. completed internships awaiting a FINAL report). */
    List<Internship> findByStatus(InternshipStatus status);

    /**
     * S7 validation queue (§5.11 step 1): one scoped paged query with the
     * filters mirrored in the count query. Every multi-hop path uses an
     * explicit JOIN so NULL supervisors/universities never drop rows.
     */
    org.springframework.data.domain.Page<Internship> searchValidationQueue(
            boolean statusesEmpty,
            java.util.Collection<InternshipStatus> statuses,
            UUID supervisorUserId,
            UUID universityId,
            InternshipType type,
            String pattern,
            org.springframework.data.domain.Pageable pageable);
}
