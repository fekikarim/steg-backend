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
    boolean existsByReference(String reference);
    List<Document> findAllByRestrictedAccessFalse();

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(d) FROM Document d WHERE d.reference LIKE :prefix%")
    long countByReferencePrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
