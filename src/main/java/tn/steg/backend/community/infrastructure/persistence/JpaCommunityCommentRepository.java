package tn.steg.backend.community.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.community.domain.model.CommunityComment;
import tn.steg.backend.community.domain.repository.CommunityCommentRepository;

import java.util.List;
import java.util.UUID;

/**
 * JPA adapter for the community comment port (T08).
 */
@Repository
public interface JpaCommunityCommentRepository
        extends JpaRepository<CommunityComment, UUID>, CommunityCommentRepository {

    @Override
    @Query(value = "select c from CommunityComment c "
            + "where c.post.id = :postId "
            + "and c.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE "
            + "order by c.createdAt asc, c.id asc",
            countQuery = "select count(c) from CommunityComment c "
                    + "where c.post.id = :postId "
                    + "and c.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE")
    Page<CommunityComment> findVisibleByPostId(@Param("postId") UUID postId, Pageable pageable);

    @Override
    @Query("select count(c) from CommunityComment c "
            + "where c.post.id = :postId "
            + "and c.status = tn.steg.backend.community.domain.model.CommunityContentStatus.VISIBLE")
    long countVisibleByPostId(@Param("postId") UUID postId);

    @Override
    List<CommunityComment> findByPostId(UUID postId);
}
