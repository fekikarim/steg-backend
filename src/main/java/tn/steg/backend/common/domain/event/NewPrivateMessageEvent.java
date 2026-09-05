package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a chat message is sent. Carries recipient IDs (resolved by the
 * publisher) plus the sender's email for display — never message content
 * beyond what the notification text itself states.
 */
public record NewPrivateMessageEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID conversationId,
        UUID messageId,
        long sequenceNumber,
        UUID senderId,
        String senderEmail,
        List<UUID> recipientIds,
        String conversationType
) implements DomainEvent {

    public NewPrivateMessageEvent(UUID conversationId, UUID messageId, long sequenceNumber,
                                  UUID senderId, String senderEmail, List<UUID> recipientIds,
                                  String conversationType) {
        this(UUID.randomUUID(), Instant.now(), senderId,
                conversationId, messageId, sequenceNumber, senderId, senderEmail,
                List.copyOf(recipientIds), conversationType);
    }
}
