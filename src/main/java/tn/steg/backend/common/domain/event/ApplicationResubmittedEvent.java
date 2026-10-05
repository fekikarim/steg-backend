package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a candidate resubmits an application after corrections
 * (MODIFICATION_REQUESTED → RESUBMITTED, AGENTS.md §4).
 */
public record ApplicationResubmittedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        String applicationReference,
        UUID candidateUserId
) implements DomainEvent {

    public ApplicationResubmittedEvent(UUID applicationId, String applicationReference,
                                       UUID candidateUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, applicationReference, candidateUserId);
    }
}
