package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinanceCaseDocumentRepository extends JpaRepository<FinanceCaseDocument, UUID>,
        tn.steg.backend.document.domain.repository.FinanceCaseDocumentRepository {
    List<FinanceCaseDocument> findByFinanceCaseId(UUID financeCaseId);
    List<FinanceCaseDocument> findByFinanceCaseIdAndDocumentRestrictedAccessFalse(UUID financeCaseId);
    Optional<FinanceCaseDocument> findByFinanceCaseIdAndDocumentId(UUID financeCaseId, UUID documentId);

    /**
     * A14 N+1 fix backing the domain port method: dossier rows for many cases
     * with their document + reviewer fetched eagerly (both rendered per row).
     */
    @Query("SELECT DISTINCT d FROM FinanceCaseDocument d " +
           "LEFT JOIN FETCH d.document LEFT JOIN FETCH d.reviewedBy " +
           "WHERE d.financeCase.id IN :financeCaseIds")
    List<FinanceCaseDocument> findByFinanceCaseIdInWithDetails(@Param("financeCaseIds") Collection<UUID> financeCaseIds);
}
