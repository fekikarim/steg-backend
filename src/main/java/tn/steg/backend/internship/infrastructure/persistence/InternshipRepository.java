package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.Internship;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipRepository extends JpaRepository<Internship, UUID> {
    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);
}
