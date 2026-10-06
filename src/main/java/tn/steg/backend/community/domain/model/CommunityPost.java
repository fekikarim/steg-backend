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
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.iam.domain.model.User;

/**
 * One student community post (T08 / ST-COM-01, BR-39).
 *
 * <p>Author identity is resolved to a privacy-safe display name at the
 * application layer (candidate first name + last initial); the row itself
 * only carries the author user id — never email, CIN, university or
 * supervisor data.
 *
 * <p>{@code commentCount} is maintained transactionally by the application
 * service (increment on visible comment create, decrement on comment
 * delete/remove) so the feed never needs a per-row COUNT.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_posts")
public class CommunityPost extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_asset_id")
    private FileAsset attachment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityContentStatus status = CommunityContentStatus.VISIBLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private User removedBy;

    @Column(name = "removed_reason", columnDefinition = "TEXT")
    private String removedReason;

    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    public CommunityPost(User author, String body, String bodyHash, FileAsset attachment) {
        this.author = author;
        this.body = body;
        this.bodyHash = bodyHash;
        this.attachment = attachment;
        this.status = CommunityContentStatus.VISIBLE;
        this.commentCount = 0;
    }

    public boolean isVisible() {
        return this.status == CommunityContentStatus.VISIBLE;
    }
}
