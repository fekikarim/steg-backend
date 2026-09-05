package tn.steg.backend.comment.application.dto;

import tn.steg.backend.comment.domain.model.Comment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        String content,
        UUID authorId,
        String authorEmail,
        UUID journalEntryId,
        UUID deliverableId,
        UUID evaluationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommentResponse from(Comment comment) {
        String authorEmail = comment.getAuthor() != null ? comment.getAuthor().getEmail() : null;
        UUID journalEntryId = comment.getJournalEntry() != null ? comment.getJournalEntry().getId() : null;
        UUID deliverableId = comment.getDeliverable() != null ? comment.getDeliverable().getId() : null;
        UUID evaluationId = comment.getEvaluation() != null ? comment.getEvaluation().getId() : null;

        return new CommentResponse(
                comment.getId(),
                comment.getContent(),
                comment.getAuthor() != null ? comment.getAuthor().getId() : null,
                authorEmail,
                journalEntryId,
                deliverableId,
                evaluationId,
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
