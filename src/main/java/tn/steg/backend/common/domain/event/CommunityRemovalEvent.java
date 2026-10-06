package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a moderator removes a community post or comment (T08 / D7).
 * The recipient is the content author; the notification carries the removal
 * reason but never the moderator identity. Related entity is always the
 * post (comment removals deep-link to the parent post).
 */
public record CommunityRemovalEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID postId,
        UUID commentId,
        UUID authorId,
        String reason,
        boolean post
) implements DomainEvent {

    public CommunityRemovalEvent(UUID postId, UUID commentId, UUID authorId,
                                 String reason, boolean post, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                postId, commentId, authorId, reason, post);
    }
}
