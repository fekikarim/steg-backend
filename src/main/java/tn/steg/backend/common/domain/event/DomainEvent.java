package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module business facts (Phase A10).
 *
 * <p>Business modules publish these via Spring's {@code ApplicationEventPublisher}
 * without depending on the notification module; the notification module depends
 * on these events (defined here in {@code common}), never the reverse — so no
 * circular dependency can form. Events carry IDs and display strings only,
 * never entities (listeners run after commit, outside the publisher's
 * persistence context).
 */
public sealed interface DomainEvent
        permits ApplicationAcceptedEvent,
        ApplicationRejectedEvent,
        InternshipAssignedEvent,
        DocumentVerifiedEvent,
        TaskAssignedEvent,
        JournalEntryValidatedEvent,
        NewPrivateMessageEvent {

    /** Unique id of this event occurrence (idempotency/tracing). */
    UUID eventId();

    /** Server time at publication. */
    Instant occurredAt();

    /** Acting user, or null for system-triggered facts. */
    UUID actorId();
}
