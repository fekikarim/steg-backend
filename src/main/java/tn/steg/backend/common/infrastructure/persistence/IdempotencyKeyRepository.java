package tn.steg.backend.common.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.common.domain.idempotency.IdempotencyKey;

/**
 * Spring Data JPA adapter for the domain
 * {@link tn.steg.backend.common.domain.idempotency.IdempotencyKeyRepository} port.
 */
@Repository
public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, UUID>,
        tn.steg.backend.common.domain.idempotency.IdempotencyKeyRepository {

    Optional<IdempotencyKey> findByKeyHashAndUserIdAndScope(String keyHash, UUID userId, String scope);
}
