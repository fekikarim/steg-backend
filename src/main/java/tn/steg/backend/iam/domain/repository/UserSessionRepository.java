package tn.steg.backend.iam.domain.repository;

import tn.steg.backend.iam.domain.model.UserSession;

import java.util.List;
import java.util.UUID;

/**
 * Domain port interface for UserSession persistence operations.
 */
public interface UserSessionRepository {
    List<UserSession> findByUserIdAndRevokedAtIsNull(UUID userId);
    long countByUserIdAndRevokedAtIsNull(UUID userId);
    UserSession save(UserSession session);
}
