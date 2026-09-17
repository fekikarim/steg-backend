package tn.steg.backend.common.domain.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

import java.util.UUID;

/**
 * E1.6 — persisted idempotency record for critical mutations.
 *
 * <p>Uniqueness on (key_hash, user_id, scope) makes double submission safe:
 * the first completed response is replayed byte-identically instead of
 * re-executing the mutation. Failures are never cached, so a failed attempt
 * can always be retried with the same key.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uq_idempotency_key_user_scope",
                columnNames = {"key_hash", "user_id", "scope"})
})
public class IdempotencyKey extends BaseEntity {

    @Column(name = "key_hash", nullable = false, length = 128)
    private String keyHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "scope", nullable = false, length = 200)
    private String scope;

    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    public IdempotencyKey(String keyHash, UUID userId, String scope,
                          Integer responseStatus, String responseBody) {
        this.keyHash = keyHash;
        this.userId = userId;
        this.scope = scope;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }
}
