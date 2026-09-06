package tn.steg.backend.application.domain.repository;

import tn.steg.backend.application.domain.model.InternshipApplication;

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
    Optional<InternshipApplication> findByIdAndCandidateUserId(UUID id, UUID userId);
    long countByReferencePrefix(String prefix);
    InternshipApplication save(InternshipApplication application);
}
