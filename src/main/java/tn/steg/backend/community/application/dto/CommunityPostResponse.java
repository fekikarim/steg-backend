package tn.steg.backend.community.application.dto;

import tn.steg.backend.community.domain.model.CommunityContentStatus;
import tn.steg.backend.community.domain.model.CommunityPost;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a community post (T08).
 *
 * <p>Privacy (D7 / BR-39): the author is exposed as an opaque
 * {@code authorUserId} (needed for mute/report actions) plus a
 * server-derived {@code authorDisplayName} — never email, CIN, university
 * or supervisor data. Removed/deleted rows never reach this model (reads
 * answer 404 instead).
 */
public record CommunityPostResponse(
        UUID id,
        UUID authorUserId,
        String authorDisplayName,
        String body,
        CommunityContentStatus status,
        int commentCount,
        AttachmentResponse attachment,
        Instant createdAt,
        Instant updatedAt
) {
    public record AttachmentResponse(
            UUID id,
            String fileName,
            String mimeType,
            Long size
    ) {}

    public static final String REDACTED_CONTENT = "[content removed by moderation]";

    public static CommunityPostResponse from(CommunityPost post, String displayName,
                                             AttachmentResponse attachment) {
        return new CommunityPostResponse(
                post.getId(),
                post.getAuthor() != null ? post.getAuthor().getId() : null,
                displayName,
                post.getBody(),
                post.getStatus(),
                post.getCommentCount(),
                attachment,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * Keyset feed window: newest-first items plus the cursor for the next
     * page (null when the feed is exhausted). Cursor-based — never offset —
     * so concurrent inserts can neither duplicate nor skip rows.
     */
    public record FeedPageResponse(
            List<CommunityPostResponse> items,
            boolean hasMore,
            Instant nextCursorTs,
            UUID nextCursorId
    ) {}
}
