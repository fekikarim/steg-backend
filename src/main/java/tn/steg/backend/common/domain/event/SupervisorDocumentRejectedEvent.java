package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the assigned supervisor REJECTS a validation document
 * (T10/ST-VAL-04): a deliverable, a journal entry or a logbook.
 *
 * <p>The rejection reason lives in the notification itself, not just the
 * audit row — unlike the Admin path ({@link DocumentRejectedEvent}), the
 * supervisor path stores no reason the student can read (journal entries
 * and deliverables keep the comment audit-only; the logbook keeps it
 * on-entity). The student must still learn WHAT to fix.
 *
 * <p>Each rejection is guarded by status transitions
 * (only SUBMITTED can be rejected), so a plain dispatch is safe: a
 * double-submit of the same decision fails with
 * {@code INVALID_STATUS_TRANSITION} before any second event exists, while
 * a reject → resubmit → reject cycle on the same document notifies again,
 * as it should.
 */
public record SupervisorDocumentRejectedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        String documentKind,
        UUID documentId,
        String documentTitle,
        String reason,
        UUID internUserId
) implements DomainEvent {

    public SupervisorDocumentRejectedEvent(UUID internshipId, String internshipReference,
                                           String documentKind, UUID documentId, String documentTitle,
                                           String reason, UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, documentKind,
                documentId, documentTitle, reason, internUserId);
    }
}
