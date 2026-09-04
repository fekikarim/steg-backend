package tn.steg.backend.candidate.domain.repository;

import tn.steg.backend.candidate.domain.model.University;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniversityRepository {
    List<University> findAll();
    Optional<University> findById(UUID id);
    Optional<University> findByCode(String code);
    University save(University university);
}
