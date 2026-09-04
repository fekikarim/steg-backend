package tn.steg.backend.iam.domain.repository;

import tn.steg.backend.iam.domain.model.RefreshToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port interface for RefreshToken persistence operations.
 */
public interface RefreshTokenRepository {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
    RefreshToken save(RefreshToken token);
    void deleteByUserId(UUID userId);
}
