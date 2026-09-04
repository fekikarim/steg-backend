package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.InternshipDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipDocumentRepository extends JpaRepository<InternshipDocument, UUID>, tn.steg.backend.document.domain.repository.InternshipDocumentRepository {
    List<InternshipDocument> findByInternshipId(UUID internshipId);
    Optional<InternshipDocument> findByInternshipIdAndDocumentId(UUID internshipId, UUID documentId);
}
