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
    /** Atomic reference counter (sequence-backed, race-free — see V51). */
    long nextReferenceSequence();
    Document save(Document document);
    Document saveAndFlush(Document document);
    List<Document> findAllByRestrictedAccessFalse();
}
