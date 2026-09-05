package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when an ACTIVE internship assignment is created (or rotated). */
public record InternshipAssignedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        UUID internUserId,
        UUID supervisorUserId,
        String departmentName
) implements DomainEvent {

    public InternshipAssignedEvent(UUID internshipId, String internshipReference,
                                   UUID internUserId, UUID supervisorUserId,
                                   String departmentName, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, internUserId, supervisorUserId, departmentName);
    }
}
