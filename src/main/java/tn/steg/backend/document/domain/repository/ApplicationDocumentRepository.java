package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.ApplicationDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationDocumentRepository {
    List<ApplicationDocument> findByApplicationId(UUID applicationId);
    List<ApplicationDocument> findByApplicationIdAndDocumentRestrictedAccessFalse(UUID applicationId);
    Optional<ApplicationDocument> findByApplicationIdAndDocumentId(UUID applicationId, UUID documentId);
    ApplicationDocument save(ApplicationDocument applicationDocument);
}
