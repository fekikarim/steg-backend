package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.FinanceCaseDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceCaseDocumentRepository {
    List<FinanceCaseDocument> findByFinanceCaseId(UUID financeCaseId);
    Optional<FinanceCaseDocument> findByFinanceCaseIdAndDocumentId(UUID financeCaseId, UUID documentId);
    FinanceCaseDocument save(FinanceCaseDocument financeCaseDocument);
}
