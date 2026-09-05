package tn.steg.backend.ai.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.ai.domain.model.AiRecommendation;
import tn.steg.backend.ai.domain.model.AiRecommendationStatus;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiRecommendationRepository extends JpaRepository<AiRecommendation, UUID>,
        tn.steg.backend.ai.domain.repository.AiRecommendationRepository {
    List<AiRecommendation> findByAnalysisId(UUID analysisId);
    List<AiRecommendation> findByStatus(AiRecommendationStatus status);
}
