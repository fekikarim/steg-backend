package tn.steg.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;

/**
 * An abuse report against one post or one comment (T08 / D7).
 *
 * <p>Reporter identity is visible to staff only — authors never learn who
 * reported them. At most one {@code OPEN} report per reporter and target
 * (enforced in the service); resolving is terminal and audited.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_reports")
public class CommunityReport extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private CommunityReportTarget targetType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_post_id")
    private CommunityPost targetPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_comment_id")
    private CommunityComment targetComment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_user_id", nullable = false)
    private User reporter;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityReportStatus status = CommunityReportStatus.OPEN;

    @Column(name = "resolution", columnDefinition = "TEXT")
    private String resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public CommunityReport(CommunityReportTarget targetType, CommunityPost targetPost,
                           CommunityComment targetComment, User reporter, String reason) {
        this.targetType = targetType;
        this.targetPost = targetPost;
        this.targetComment = targetComment;
        this.reporter = reporter;
        this.reason = reason;
        this.status = CommunityReportStatus.OPEN;
    }

    public boolean isOpen() {
        return this.status == CommunityReportStatus.OPEN;
    }
}
