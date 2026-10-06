package tn.steg.backend.community.domain.repository;

import tn.steg.backend.community.domain.model.CommunityMute;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for community mutes (T08 / D7).
 */
public interface CommunityMuteRepository {

    Optional<CommunityMute> findByUserId(UUID userId);

    CommunityMute save(CommunityMute mute);

    void delete(CommunityMute mute);
}
