package tn.steg.backend.messaging.application.dto;

import java.util.UUID;

/**
 * Unread aggregation per conversation for the current user.
 */
public record UnreadCountResponse(
        UUID conversationId,
        Long unreadCount,
        Long lastSequenceNumber
) {}
