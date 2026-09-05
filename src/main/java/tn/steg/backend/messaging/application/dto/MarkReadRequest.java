package tn.steg.backend.messaging.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload for marking a conversation read up to a sequence number.
 * Updates {@code ConversationMember.lastReadAt} and drives unread counts.
 */
public record MarkReadRequest(
        @NotNull(message = "upToSequenceNumber must not be null")
        @Positive(message = "upToSequenceNumber must be positive")
        Long upToSequenceNumber
) {}
