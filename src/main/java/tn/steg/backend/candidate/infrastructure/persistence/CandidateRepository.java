package tn.steg.backend.candidate.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.candidate.domain.model.Candidate;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID> {
    Optional<Candidate> findByNationalIdHash(String nationalIdHash);
    Optional<Candidate> findByEmail(String email);
    boolean existsByNationalIdHash(String nationalIdHash);
}
