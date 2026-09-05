package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when an application workflow reaches FINAL_DECISION/APPROVED. */
public record ApplicationAcceptedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        String applicationReference,
        UUID candidateUserId
) implements DomainEvent {

    public ApplicationAcceptedEvent(UUID applicationId, String applicationReference,
                                    UUID candidateUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, applicationReference, candidateUserId);
    }
}
