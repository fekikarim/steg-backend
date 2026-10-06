package tn.steg.backend.community.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.community.domain.model.CommunityReport;
import tn.steg.backend.community.domain.model.CommunityReportStatus;
import tn.steg.backend.community.domain.model.CommunityReportTarget;
import tn.steg.backend.community.domain.repository.CommunityReportRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the community report port (T08 / D7).
 */
@Repository
public interface JpaCommunityReportRepository
        extends JpaRepository<CommunityReport, UUID>, CommunityReportRepository {

    @Override
    Page<CommunityReport> findByStatus(CommunityReportStatus status, Pageable pageable);

    @Override
    Page<CommunityReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Override
    @Query("select r from CommunityReport r "
            + "where r.reporter.id = :reporterUserId "
            + "and r.targetPost.id = :postId "
            + "and r.status = tn.steg.backend.community.domain.model.CommunityReportStatus.OPEN")
    Optional<CommunityReport> findOpenByReporterAndPost(
            @Param("reporterUserId") UUID reporterUserId,
            @Param("postId") UUID postId);

    @Override
    @Query("select r from CommunityReport r "
            + "where r.reporter.id = :reporterUserId "
            + "and r.targetComment.id = :commentId "
            + "and r.status = tn.steg.backend.community.domain.model.CommunityReportStatus.OPEN")
    Optional<CommunityReport> findOpenByReporterAndComment(
            @Param("reporterUserId") UUID reporterUserId,
            @Param("commentId") UUID commentId);

    @Override
    boolean existsByTargetTypeAndTargetPostId(CommunityReportTarget targetType, UUID postId);

    @Override
    boolean existsByTargetTypeAndTargetCommentId(CommunityReportTarget targetType, UUID commentId);
}
