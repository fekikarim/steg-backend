package tn.steg.backend.community.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.community.domain.model.CommunityComment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for community comments (T08).
 */
public interface CommunityCommentRepository {

    Optional<CommunityComment> findById(UUID id);

    CommunityComment save(CommunityComment comment);

    /**
     * Chronological thread of one post (visible rows only,
     * {@code createdAt ASC, id ASC}).
     */
    Page<CommunityComment> findVisibleByPostId(UUID postId, Pageable pageable);

    /** Visible comment count of one post (reconciliation guard). */
    long countVisibleByPostId(UUID postId);

    /** All comments of one post (any status) for moderation cleanup reads. */
    List<CommunityComment> findByPostId(UUID postId);
}
