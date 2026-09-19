package tn.steg.backend.candidate.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.candidate.domain.model.Candidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID>, tn.steg.backend.candidate.domain.repository.CandidateRepository {
    Optional<Candidate> findByUserId(UUID userId);
    Optional<Candidate> findByNationalIdHash(String nationalIdHash);
    Optional<Candidate> findByEmail(String email);
    List<Candidate> findByEmailIgnoreCase(String email);
    List<Candidate> findByPhone(String phone);
    boolean existsByNationalIdHash(String nationalIdHash);
}
