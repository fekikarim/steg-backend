package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when an internship certificate is generated and downloadable. */
public record CertificateAvailableEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID certificateId,
        String certificateReference,
        UUID internshipId,
        UUID internUserId
) implements DomainEvent {

    public CertificateAvailableEvent(UUID certificateId, String certificateReference,
                                     UUID internshipId, UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                certificateId, certificateReference, internshipId, internUserId);
    }
}
