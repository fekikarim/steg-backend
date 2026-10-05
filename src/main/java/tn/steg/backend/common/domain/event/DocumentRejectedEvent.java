package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the Admin REJECTS a validation document (S7, assumption
 * #18): the candidate must be told WHAT to fix — the notification carries the
 * rejection comment, and the internship is already back at REPORT_SUBMITTED
 * for resubmission.
 */
public record DocumentRejectedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        String documentType,
        String comment,
        UUID candidateUserId
) implements DomainEvent {

    public DocumentRejectedEvent(UUID internshipId, String internshipReference,
                                 String documentType, String comment,
                                 UUID candidateUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, documentType, comment, candidateUserId);
    }
}
