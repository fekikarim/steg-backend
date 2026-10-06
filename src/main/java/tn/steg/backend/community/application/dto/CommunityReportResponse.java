package tn.steg.backend.community.application.dto;

import tn.steg.backend.community.domain.model.CommunityReport;
import tn.steg.backend.community.domain.model.CommunityReportStatus;
import tn.steg.backend.community.domain.model.CommunityReportTarget;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a community report (T08 / D7, staff only).
 *
 * <p>Reporter identity stays staff-visible: this model is never served to
 * post/comment authors. Resolution metadata (who resolved, when) is audit
 * detail for the moderation queue.
 */
public record CommunityReportResponse(
        UUID id,
        CommunityReportTarget targetType,
        UUID targetPostId,
        UUID targetCommentId,
        UUID reporterUserId,
        String reason,
        CommunityReportStatus status,
        String resolution,
        UUID resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {
    public static CommunityReportResponse from(CommunityReport report) {
        return new CommunityReportResponse(
                report.getId(),
                report.getTargetType(),
                report.getTargetPost() != null ? report.getTargetPost().getId() : null,
                report.getTargetComment() != null ? report.getTargetComment().getId() : null,
                report.getReporter() != null ? report.getReporter().getId() : null,
                report.getReason(),
                report.getStatus(),
                report.getResolution(),
                report.getResolvedBy() != null ? report.getResolvedBy().getId() : null,
                report.getResolvedAt(),
                report.getCreatedAt());
    }
}
