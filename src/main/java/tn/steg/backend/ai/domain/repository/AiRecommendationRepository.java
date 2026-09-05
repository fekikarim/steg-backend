package tn.steg.backend.ai.domain.repository;

import tn.steg.backend.ai.domain.model.AiRecommendation;

import java.util.Optional;
import java.util.UUID;

public interface AiRecommendationRepository {
    Optional<AiRecommendation> findById(UUID id);
    AiRecommendation save(AiRecommendation recommendation);
}
