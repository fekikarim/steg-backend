package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an internship reaches COMPLETED. Pure business fact: the
 * evaluation module turns it into a "final evaluation required" fact when the
 * internship has no FINAL report yet.
 */
public record InternshipCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        String candidateName
) implements DomainEvent {

    public InternshipCompletedEvent(UUID internshipId, String internshipReference,
                                    String candidateName, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, candidateName);
    }
}
