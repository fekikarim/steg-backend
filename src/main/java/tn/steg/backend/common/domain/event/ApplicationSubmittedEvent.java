package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a candidate submits an application (DRAFT → SUBMITTED). */
public record ApplicationSubmittedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        String applicationReference,
        UUID candidateUserId
) implements DomainEvent {

    public ApplicationSubmittedEvent(UUID applicationId, String applicationReference,
                                     UUID candidateUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, applicationReference, candidateUserId);
    }
}
