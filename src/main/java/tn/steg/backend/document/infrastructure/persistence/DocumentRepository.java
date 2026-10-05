package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID>, tn.steg.backend.document.domain.repository.DocumentRepository {
    Optional<Document> findByReference(String reference);
    List<Document> findAllByRestrictedAccessFalse();

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT nextval('document_reference_seq')", nativeQuery = true)
    long nextReferenceSequence();
}
