package tn.steg.backend.application.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipApplicationRepository {
    List<InternshipApplication> findAll();
    /**
     * A14 N+1 fix: staff list views render candidate/reviewer names per row.
     * Fetch both associations in the single list query.
     */
    List<InternshipApplication> findAllWithCandidate();
    Optional<InternshipApplication> findById(UUID id);
    Optional<InternshipApplication> findByReference(String reference);
    boolean existsByReference(String reference);
    List<InternshipApplication> findByCandidateId(UUID candidateId);
    /** S5 candidate queue: one query for the application counts/latest status of a whole page. */
    List<InternshipApplication> findByCandidateIdIn(java.util.Collection<UUID> candidateIds);
    Optional<InternshipApplication> findByIdAndCandidateUserId(UUID id, UUID userId);
    boolean existsByCandidateId(UUID candidateId);
    boolean existsByCandidateIdAndStatusNot(UUID candidateId, tn.steg.backend.application.domain.model.ApplicationStatus status);
    Optional<InternshipApplication> findByTrackingTokenHash(String trackingTokenHash);
    long countByReferencePrefix(String prefix);

    /**
     * Staff application queue (AGENTS.md §5.2 / §6.2): server-side pagination,
     * search and filters in a single query.
     *
     * <p>Scoping is part of the query, never of the client: {@code scoped=false}
     * is the Admin (all applications), {@code scoped=true} restricts the result
     * to {@code candidateIds} (the Supervisor's own candidates). Callers must
     * pass a non-empty {@code candidateIds} list when {@code scoped=true}.
     *
     * @param status      exact application status, or {@code null} for all
     * @param type        calculated internship type, or {@code null} for all
     * @param universityName exact university name in lower case (matched against
     *                       {@code LOWER(candidate.university.name)}), or {@code null}
     * @param submissionFrom inclusive lower bound on the submission date, or {@code null}
     * @param submissionTo   inclusive upper bound on the submission date, or {@code null}
     * @param searchPattern  SQL LIKE pattern (lower-cased) over reference, candidate
     *                       name and candidate email, or {@code null}
     */
    Page<InternshipApplication> searchStaffApplications(
            boolean scoped,
            List<UUID> candidateIds,
            ApplicationStatus status,
            InternshipType type,
            String universityName,
            LocalDate submissionFrom,
            LocalDate submissionTo,
            String searchPattern,
            Pageable pageable);
    @org.springframework.data.jpa.repository.Query(value = "SELECT reference FROM internship_applications WHERE reference LIKE :prefix || '%' ORDER BY reference DESC LIMIT 1", nativeQuery = true)
    Optional<String> findTopReferenceByPrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
    InternshipApplication save(InternshipApplication application);
}
