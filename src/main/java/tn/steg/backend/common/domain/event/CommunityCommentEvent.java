package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a community comment lands on someone else's post (T08).
 * The recipient is the post author; the notification deep-links to the
 * post (relatedEntityType {@code CommunityPost}, relatedEntityId = post).
 * Carries IDs and display strings only — never comment content beyond what
 * the notification text itself states.
 */
public record CommunityCommentEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID postId,
        UUID commentId,
        UUID postAuthorId,
        String commenterDisplayName
) implements DomainEvent {

    public CommunityCommentEvent(UUID postId, UUID commentId, UUID postAuthorId,
                                 String commenterDisplayName, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                postId, commentId, postAuthorId, commenterDisplayName);
    }
}
