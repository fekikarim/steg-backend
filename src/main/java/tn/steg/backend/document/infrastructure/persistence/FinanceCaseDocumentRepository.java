package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinanceCaseDocumentRepository extends JpaRepository<FinanceCaseDocument, UUID>,
        tn.steg.backend.document.domain.repository.FinanceCaseDocumentRepository {
    List<FinanceCaseDocument> findByFinanceCaseId(UUID financeCaseId);
    Optional<FinanceCaseDocument> findByFinanceCaseIdAndDocumentId(UUID financeCaseId, UUID documentId);
}
