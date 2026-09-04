package tn.steg.backend.application.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.InternshipApplication;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipApplicationRepository extends JpaRepository<InternshipApplication, UUID> {
    Optional<InternshipApplication> findByReference(String reference);
    boolean existsByReference(String reference);
}
