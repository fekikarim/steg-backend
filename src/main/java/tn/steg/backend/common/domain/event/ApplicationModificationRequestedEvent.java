package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the Admin requests modifications on an application
 * (AGENTS.md §5.2: the candidate must be informed; email is degraded-safe).
 */
public record ApplicationModificationRequestedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        String applicationReference,
        UUID candidateUserId,
        String message
) implements DomainEvent {

    public ApplicationModificationRequestedEvent(UUID applicationId, String applicationReference,
                                                 UUID candidateUserId, UUID actorId, String message) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, applicationReference, candidateUserId, message);
    }
}
