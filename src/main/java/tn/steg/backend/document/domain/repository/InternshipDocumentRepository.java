package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.InternshipDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipDocumentRepository {
    List<InternshipDocument> findByInternshipId(UUID internshipId);
    Optional<InternshipDocument> findByInternshipIdAndDocumentId(UUID internshipId, UUID documentId);
    InternshipDocument save(InternshipDocument internshipDocument);
}
