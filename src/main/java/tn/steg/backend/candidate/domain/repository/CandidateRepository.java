package tn.steg.backend.candidate.domain.repository;

import tn.steg.backend.candidate.domain.model.Candidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateRepository {
    List<Candidate> findAll();
    Optional<Candidate> findById(UUID id);
    Optional<Candidate> findByUserId(UUID userId);
    Optional<Candidate> findByNationalIdHash(String nationalIdHash);
    Optional<Candidate> findByEmail(String email);
    boolean existsByNationalIdHash(String nationalIdHash);
    Candidate save(Candidate candidate);
}
