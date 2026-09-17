package tn.steg.backend.common.domain.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for idempotency records (E1.6).
 */
public interface IdempotencyKeyRepository {
    Optional<IdempotencyKey> findByKeyHashAndUserIdAndScope(String keyHash, UUID userId, String scope);
    IdempotencyKey save(IdempotencyKey key);
}
