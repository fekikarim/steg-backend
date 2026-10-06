package tn.steg.backend.community.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.community.domain.model.CommunityMute;
import tn.steg.backend.community.domain.repository.CommunityMuteRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the community mute port (T08 / D7).
 */
@Repository
public interface JpaCommunityMuteRepository
        extends JpaRepository<CommunityMute, UUID>, CommunityMuteRepository {

    @Override
    Optional<CommunityMute> findByUserId(UUID userId);
}
