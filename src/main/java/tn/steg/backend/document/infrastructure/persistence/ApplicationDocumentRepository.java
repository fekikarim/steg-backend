package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.ApplicationDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, UUID>, tn.steg.backend.document.domain.repository.ApplicationDocumentRepository {
    List<ApplicationDocument> findByApplicationId(UUID applicationId);
    Optional<ApplicationDocument> findByApplicationIdAndDocumentId(UUID applicationId, UUID documentId);
    long countByApplicationIdAndMandatoryTrueAndVerificationStatus(UUID applicationId, tn.steg.backend.document.domain.model.DocumentVerificationStatus status);
}
