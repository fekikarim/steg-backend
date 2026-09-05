package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipRepository {
    List<Internship> findAll();
    Optional<Internship> findById(UUID id);
    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
    Internship save(Internship internship);
    List<Internship> findByCandidateId(UUID candidateId);
    List<Internship> findByCandidateUserIdAndStatus(UUID userId, InternshipStatus status);
}
