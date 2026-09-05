package tn.steg.backend.ai.domain.repository;

import tn.steg.backend.ai.domain.model.AiAnalysis;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiAnalysisRepository {
    Optional<AiAnalysis> findById(UUID id);
    AiAnalysis save(AiAnalysis analysis);
    List<AiAnalysis> findByRelatedEntityTypeAndRelatedEntityId(String relatedEntityType, UUID relatedEntityId);
}
