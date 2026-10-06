package tn.steg.backend.community.application.dto;

import tn.steg.backend.community.domain.model.CommunityComment;
import tn.steg.backend.community.domain.model.CommunityContentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a community comment (T08). Same privacy rule as posts:
 * opaque author id + server-derived display name only.
 */
public record CommunityCommentResponse(
        UUID id,
        UUID postId,
        UUID authorUserId,
        String authorDisplayName,
        String body,
        CommunityContentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommunityCommentResponse from(CommunityComment comment, String displayName) {
        return new CommunityCommentResponse(
                comment.getId(),
                comment.getPost() != null ? comment.getPost().getId() : null,
                comment.getAuthor() != null ? comment.getAuthor().getId() : null,
                displayName,
                comment.getBody(),
                comment.getStatus(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
