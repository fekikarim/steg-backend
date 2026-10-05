package tn.steg.backend.internship.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.internship.domain.model.DocumentValidationDecision;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;
import tn.steg.backend.internship.domain.model.VerificationRun;
import tn.steg.backend.internship.domain.repository.ValidationRepository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter behind the validation domain port. Runs and decisions
 * are append-only history; nothing here ever mutates the internship aggregate
 * (status moves go through {@code InternshipLifecycleService} only, S6b).
 */
@Component
@RequiredArgsConstructor
public class ValidationRepositoryAdapter implements ValidationRepository {

    private final JpaVerificationRunRepository runRepository;
    private final JpaValidationDecisionRepository decisionRepository;

    @Override
    public VerificationRun saveRun(VerificationRun run) {
        return runRepository.save(run);
    }

    @Override
    public List<VerificationRun> findRunsByInternshipIdOrderByRunAtDesc(UUID internshipId) {
        return runRepository.findByInternshipIdOrderByRunAtDesc(internshipId);
    }

    @Override
    public List<VerificationRun> findRunsByInternshipIdAndDocumentTypeOrderByRunAtDesc(
            UUID internshipId, ValidationDocumentType documentType) {
        return runRepository.findByInternshipIdAndDocumentTypeOrderByRunAtDesc(internshipId, documentType);
    }

    @Override
    public DocumentValidationDecision saveDecision(DocumentValidationDecision decision) {
        return decisionRepository.save(decision);
    }

    @Override
    public List<DocumentValidationDecision> findDecisionsByInternshipIdOrderByDecidedAtDesc(UUID internshipId) {
        return decisionRepository.findByInternshipIdOrderByDecidedAtDesc(internshipId);
    }
}
