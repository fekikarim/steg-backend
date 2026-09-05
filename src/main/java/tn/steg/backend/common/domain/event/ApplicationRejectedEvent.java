package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when an application workflow reaches FINAL_DECISION/REJECTED. */
public record ApplicationRejectedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        String applicationReference,
        UUID candidateUserId,
        String reason
) implements DomainEvent {

    public ApplicationRejectedEvent(UUID applicationId, String applicationReference,
                                    UUID candidateUserId, UUID actorId, String reason) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, applicationReference, candidateUserId, reason);
    }
}
