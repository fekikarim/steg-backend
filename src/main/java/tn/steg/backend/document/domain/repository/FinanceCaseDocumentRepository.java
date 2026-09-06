package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.FinanceCaseDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceCaseDocumentRepository {
    List<FinanceCaseDocument> findByFinanceCaseId(UUID financeCaseId);
    /**
     * A14 N+1 fix: bulk-load dossier rows for a whole finance-case page in one
     * query instead of one query per finance case. The linked document and
     * reviewer are fetch-joined by the infrastructure adapter (see
     * {@code document.infrastructure.persistence.FinanceCaseDocumentRepository}).
     */
    List<FinanceCaseDocument> findByFinanceCaseIdInWithDetails(Collection<UUID> financeCaseIds);
    List<FinanceCaseDocument> findByFinanceCaseIdAndDocumentRestrictedAccessFalse(UUID financeCaseId);
    Optional<FinanceCaseDocument> findByFinanceCaseIdAndDocumentId(UUID financeCaseId, UUID documentId);
    FinanceCaseDocument save(FinanceCaseDocument financeCaseDocument);
}
