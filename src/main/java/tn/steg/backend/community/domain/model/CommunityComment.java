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

/**
 * One comment on a community post (T08 / ST-COM-01). Flat thread (no
 * nesting), text-only, same lifecycle as posts.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_comments")
public class CommunityComment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityContentStatus status = CommunityContentStatus.VISIBLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private User removedBy;

    @Column(name = "removed_reason", columnDefinition = "TEXT")
    private String removedReason;

    public CommunityComment(CommunityPost post, User author, String body) {
        this.post = post;
        this.author = author;
        this.body = body;
        this.status = CommunityContentStatus.VISIBLE;
    }

    public boolean isVisible() {
        return this.status == CommunityContentStatus.VISIBLE;
    }
}
