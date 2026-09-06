package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;

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
    List<Internship> findByCandidateUserIdAndStatus(UUID userId, InternshipStatus status);
}
