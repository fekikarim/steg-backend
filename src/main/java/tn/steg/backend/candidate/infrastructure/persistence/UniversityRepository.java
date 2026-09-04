package tn.steg.backend.candidate.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.candidate.domain.model.University;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UniversityRepository extends JpaRepository<University, UUID>, tn.steg.backend.candidate.domain.repository.UniversityRepository {
    Optional<University> findByCode(String code);
}
