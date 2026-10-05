package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an internship advances along the explicit status model of
 * AGENTS.md §4 (APPROVED → IN_PROGRESS → REPORT_SUBMITTED → UNDER_VALIDATION
 * → VALIDATED → RECEIPT_ISSUED).
 *
 * <p>Carries the supervisor and intern user ids resolved at transition time so
 * the notification fan-out never has to re-implement supervision scoping.
 */
public record InternshipStatusChangedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID internshipId,
        String internshipReference,
        String previousStatus,
        String newStatus,
        String comment,
        UUID supervisorUserId,
        UUID internUserId
) implements DomainEvent {

    public InternshipStatusChangedEvent(UUID internshipId, String internshipReference,
                                        String previousStatus, String newStatus, String comment,
                                        UUID supervisorUserId, UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                internshipId, internshipReference, previousStatus, newStatus, comment,
                supervisorUserId, internUserId);
    }
}
