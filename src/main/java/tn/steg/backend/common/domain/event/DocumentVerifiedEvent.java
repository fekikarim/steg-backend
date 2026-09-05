package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when staff verify (or reject / request correction on) an application document. */
public record DocumentVerifiedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID applicationId,
        UUID documentId,
        String documentType,
        String verificationStatus,
        UUID candidateUserId
) implements DomainEvent {

    public DocumentVerifiedEvent(UUID applicationId, UUID documentId, String documentType,
                                 String verificationStatus, UUID candidateUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                applicationId, documentId, documentType, verificationStatus, candidateUserId);
    }
}
