package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for Document entities.
 */
public interface DocumentRepository {
    Optional<Document> findById(UUID id);
    Optional<Document> findByReference(String reference);
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
    Document save(Document document);
    Document saveAndFlush(Document document);
    List<Document> findAllByRestrictedAccessFalse();
}
