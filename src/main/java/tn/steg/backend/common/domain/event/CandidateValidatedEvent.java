package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a candidate validates their front-office account
 * (AGENTS.md §8.1): either by creating their candidate profile through
 * self-registration, or by claiming a staff-created profile with the same
 * CIN or email. Staff-created profiles without an account never publish
 * this event — nobody validated anything yet.
 */
public record CandidateValidatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID candidateId,
        UUID candidateUserId,
        String candidateEmail,
        String candidateName
) implements DomainEvent {

    public CandidateValidatedEvent(UUID candidateId, UUID candidateUserId,
                                   String candidateEmail, String candidateName, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                candidateId, candidateUserId, candidateEmail, candidateName);
    }
}
