package tn.steg.backend.common.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a finance case reaches APPROVED and its receipt is issued. */
public record PaymentApprovedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID financeCaseId,
        String financeCaseReference,
        UUID supervisorUserId,
        BigDecimal amount,
        int paidMonths
) implements DomainEvent {

    public PaymentApprovedEvent(UUID financeCaseId, String financeCaseReference,
                                UUID supervisorUserId, BigDecimal amount, int paidMonths, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                financeCaseId, financeCaseReference, supervisorUserId, amount, paidMonths);
    }
}
