package tn.steg.backend.messaging.application.dto;

import tn.steg.backend.messaging.domain.model.Message;
import tn.steg.backend.messaging.domain.model.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a chat message.
 * Soft-deleted messages are redacted: content is replaced and attachments hidden,
 * but id/sequenceNumber/sender/timestamps are preserved for audit/history.
 */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String content,
        MessageStatus status,
        Long sequenceNumber,
        Instant sentAt,
        Instant editedAt,
        Instant deletedAt,
        List<AttachmentResponse> attachments
) {
    public record AttachmentResponse(
            UUID id,
            UUID fileAssetId,
            String fileName,
            String mimeType,
            Long size
    ) {}

    public static final String REDACTED_CONTENT = "[message deleted]";

    public static MessageResponse from(Message message, List<AttachmentResponse> attachments) {
        boolean deleted = message.getDeletedAt() != null || message.getStatus() == MessageStatus.DELETED;
        return new MessageResponse(
                message.getId(),
                message.getConversation() != null ? message.getConversation().getId() : null,
                message.getSender() != null ? message.getSender().getId() : null,
                deleted ? REDACTED_CONTENT : message.getContent(),
                message.getStatus(),
                message.getSequenceNumber(),
                message.getSentAt(),
                message.getEditedAt(),
                message.getDeletedAt(),
                deleted ? List.of() : (attachments != null ? attachments : List.of()));
    }
}
