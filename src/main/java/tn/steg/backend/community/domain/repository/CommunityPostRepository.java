package tn.steg.backend.community.domain.repository;

import org.springframework.data.domain.Pageable;
import tn.steg.backend.community.domain.model.CommunityPost;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for community posts (T08).
 */
public interface CommunityPostRepository {

    Optional<CommunityPost> findById(UUID id);

    CommunityPost save(CommunityPost post);

    /**
     * Newest-first keyset window over visible posts: rows strictly older
     * than {@code (cursorCreatedAt, cursorId)} in {@code (createdAt DESC,
     * id DESC)} order. Two methods (not nullable parameters) so Postgres
     * always knows the parameter types.
     */
    List<CommunityPost> findVisibleFeedFirstPage(Pageable pageable);

    List<CommunityPost> findVisibleFeedPageAfter(Instant cursorCreatedAt, UUID cursorId, Pageable pageable);

    /**
     * Recent visible posts by one author (duplicate-content window guard).
     */
    List<CommunityPost> findRecentVisibleByAuthor(UUID authorUserId, Instant since, Pageable pageable);

    long countVisible();

    /**
     * Owning visible post of one attachment (single-purpose download guard,
     * no scan).
     */
    Optional<UUID> findVisiblePostIdByAttachmentId(UUID attachmentId);
}
