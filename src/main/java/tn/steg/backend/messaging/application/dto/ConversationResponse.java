package tn.steg.backend.messaging.application.dto;

import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a conversation visible to the current user.
 * Never exposes membership of conversations the caller cannot see
 * (the service only returns rows the caller actively belongs to,
 * except for staff with an explicit override).
 */
public record ConversationResponse(
        UUID id,
        ConversationType type,
        String title,
        Boolean archived,
        UUID internshipId,
        Instant createdAt,
        Instant updatedAt,
        List<MemberResponse> members,
        Long lastSequenceNumber,
        Long unreadCount
) {
    public record MemberResponse(
            UUID userId,
            String role,
            Instant joinedAt,
            Instant lastReadAt
    ) {}

    public static ConversationResponse from(
            Conversation conversation,
            List<MemberResponse> members,
            Long lastSequenceNumber,
            Long unreadCount) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getType(),
                conversation.getTitle(),
                conversation.getArchived(),
                conversation.getInternship() != null ? conversation.getInternship().getId() : null,
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                members != null ? members : List.of(),
                lastSequenceNumber,
                unreadCount != null ? unreadCount : 0L);
    }
}
