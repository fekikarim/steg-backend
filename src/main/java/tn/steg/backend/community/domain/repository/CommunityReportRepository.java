package tn.steg.backend.community.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.community.domain.model.CommunityReport;
import tn.steg.backend.community.domain.model.CommunityReportStatus;
import tn.steg.backend.community.domain.model.CommunityReportTarget;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for community reports (T08 / D7).
 */
public interface CommunityReportRepository {

    Optional<CommunityReport> findById(UUID id);

    CommunityReport save(CommunityReport report);

    /** Moderation queue: newest first, optional status filter. */
    Page<CommunityReport> findByStatus(CommunityReportStatus status, Pageable pageable);

    /** Any-status moderation queue (newest first). */
    Page<CommunityReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Open report by one reporter against one post, if any. */
    Optional<CommunityReport> findOpenByReporterAndPost(UUID reporterUserId, UUID postId);

    /** Open report by one reporter against one comment, if any. */
    Optional<CommunityReport> findOpenByReporterAndComment(UUID reporterUserId, UUID commentId);

    /** Whether any report (any status) targets the post — cleanup guard. */
    boolean existsByTargetTypeAndTargetPostId(CommunityReportTarget targetType, UUID postId);

    /** Whether any report targets the comment — cleanup guard. */
    boolean existsByTargetTypeAndTargetCommentId(CommunityReportTarget targetType, UUID commentId);
}
