package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.DocumentValidationDecision;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;
import tn.steg.backend.internship.domain.model.VerificationRun;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain ports for S7 validation persistence (runs are advisory history,
 * decisions are immutable history whose latest row per document is current).
 */
public interface ValidationRepository {
    VerificationRun saveRun(VerificationRun run);

    List<VerificationRun> findRunsByInternshipIdOrderByRunAtDesc(UUID internshipId);

    List<VerificationRun> findRunsByInternshipIdAndDocumentTypeOrderByRunAtDesc(
            UUID internshipId, ValidationDocumentType documentType);

    DocumentValidationDecision saveDecision(DocumentValidationDecision decision);

    List<DocumentValidationDecision> findDecisionsByInternshipIdOrderByDecidedAtDesc(UUID internshipId);

    default Optional<DocumentValidationDecision> latestDecision(
            UUID internshipId, ValidationDocumentType documentType) {
        return findDecisionsByInternshipIdOrderByDecidedAtDesc(internshipId).stream()
                .filter(d -> d.getDocumentType() == documentType)
                .findFirst();
    }
}
