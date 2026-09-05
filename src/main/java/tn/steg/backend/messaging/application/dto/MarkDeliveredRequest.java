package tn.steg.backend.messaging.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload for acknowledging delivery up to a sequence number.
 * Delivery acknowledgement is distinct from persistence (server stored)
 * and from read acknowledgement (member actually read).
 * Reading always implies delivery.
 */
public record MarkDeliveredRequest(
        @NotNull(message = "upToSequenceNumber must not be null")
        @Positive(message = "upToSequenceNumber must be positive")
        Long upToSequenceNumber
) {}
