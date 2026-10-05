package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a COMPLETED internship still has no FINAL evaluation — the
 * administrative validation stays blocked until the report is recorded, so
 * staff must be alerted (notifications) and the Back Office refreshed.
 */
public record FinalEvaluationRequiredEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        String candidateName
) implements DomainEvent {

    public FinalEvaluationRequiredEvent(UUID internshipId, String internshipReference,
                                        String candidateName, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, candidateName);
    }
}
