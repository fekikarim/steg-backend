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
    boolean existsByCandidateId(UUID candidateId);
    boolean existsByCandidateIdAndStatusNot(UUID candidateId, tn.steg.backend.application.domain.model.ApplicationStatus status);
    Optional<InternshipApplication> findByTrackingTokenHash(String trackingTokenHash);
    long countByReferencePrefix(String prefix);
    @org.springframework.data.jpa.repository.Query(value = "SELECT reference FROM internship_applications WHERE reference LIKE :prefix || '%' ORDER BY reference DESC LIMIT 1", nativeQuery = true)
    Optional<String> findTopReferenceByPrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
    InternshipApplication save(InternshipApplication application);
}
