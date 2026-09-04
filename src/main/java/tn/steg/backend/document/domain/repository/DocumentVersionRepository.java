package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.DocumentVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository {
    Optional<DocumentVersion> findById(UUID id);
    List<DocumentVersion> findByDocumentIdOrderByVersionNumberDesc(UUID documentId);
    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);
    DocumentVersion save(DocumentVersion documentVersion);
}
