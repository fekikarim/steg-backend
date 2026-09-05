package tn.steg.backend.ai.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.ai.domain.model.AiAnalysis;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiAnalysisRepository extends JpaRepository<AiAnalysis, UUID>,
        tn.steg.backend.ai.domain.repository.AiAnalysisRepository {
    List<AiAnalysis> findByRelatedEntityTypeAndRelatedEntityId(String relatedEntityType, UUID relatedEntityId);
}
