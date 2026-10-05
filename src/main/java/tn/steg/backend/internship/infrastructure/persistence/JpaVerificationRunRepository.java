package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;
import tn.steg.backend.internship.domain.model.VerificationRun;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaVerificationRunRepository extends JpaRepository<VerificationRun, UUID> {
    List<VerificationRun> findByInternshipIdOrderByRunAtDesc(UUID internshipId);

    List<VerificationRun> findByInternshipIdAndDocumentTypeOrderByRunAtDesc(
            UUID internshipId, ValidationDocumentType documentType);
}
