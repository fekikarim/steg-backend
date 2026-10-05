package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the intern's report submission moves the internship
 * IN_PROGRESS → REPORT_SUBMITTED (S7a.1, assumption A8: the deliverables
 * channel is the submission step). The lifecycle's own status-changed event
 * already notifies the supervisor and the intern; this event exists so the
 * Admin role is fanned out too — the Admin owns the validation queue (§5.11),
 * exactly like a new application request (§8.1).
 */
public record InternshipReportSubmittedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        UUID deliverableId
) implements DomainEvent {

    public InternshipReportSubmittedEvent(UUID internshipId, String internshipReference,
                                          UUID deliverableId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, deliverableId);
    }
}
