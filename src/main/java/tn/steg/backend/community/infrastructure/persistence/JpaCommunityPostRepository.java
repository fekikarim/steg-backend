package tn.steg.backend.community.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.community.domain.model.CommunityPost;
import tn.steg.backend.community.domain.repository.CommunityPostRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the community post port (T08).
 */
@Repository
public interface JpaCommunityPostRepository
        extends JpaRepository<CommunityPost, UUID>, CommunityPostRepository {

    @Override
    @Query("select p from CommunityPost p "
            + "where p.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE "
            + "order by p.createdAt desc, p.id desc")
    List<CommunityPost> findVisibleFeedFirstPage(Pageable pageable);

    @Override
    @Query("select p from CommunityPost p "
            + "where p.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE "
            + "and (p.createdAt < :cursorCreatedAt "
            + "or (p.createdAt = :cursorCreatedAt and p.id < :cursorId)) "
            + "order by p.createdAt desc, p.id desc")
    List<CommunityPost> findVisibleFeedPageAfter(
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Override
    @Query("select p from CommunityPost p "
            + "where p.author.id = :authorUserId "
            + "and p.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE "
            + "and p.createdAt >= :since "
            + "order by p.createdAt desc")
    List<CommunityPost> findRecentVisibleByAuthor(
            @Param("authorUserId") UUID authorUserId,
            @Param("since") Instant since,
            Pageable pageable);

    @Override
    @Query("select count(p) from CommunityPost p "
            + "where p.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE")
    long countVisible();

    @Override
    @Query("select p.id from CommunityPost p "
            + "where p.attachment.id = :attachmentId "
            + "and p.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE")
    Optional<UUID> findVisiblePostIdByAttachmentId(@Param("attachmentId") UUID attachmentId);
}
