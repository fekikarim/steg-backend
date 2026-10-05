package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.DocumentValidationDecision;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaValidationDecisionRepository extends JpaRepository<DocumentValidationDecision, UUID> {
    List<DocumentValidationDecision> findByInternshipIdOrderByDecidedAtDesc(UUID internshipId);
}
