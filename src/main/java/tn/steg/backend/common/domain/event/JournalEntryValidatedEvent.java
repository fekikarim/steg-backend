package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a supervisor validates a journal entry. */
public record JournalEntryValidatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID entryId,
        String entryTitle,
        UUID internshipId,
        UUID internUserId
) implements DomainEvent {

    public JournalEntryValidatedEvent(UUID entryId, String entryTitle, UUID internshipId,
                                      UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                entryId, entryTitle, internshipId, internUserId);
    }
}
