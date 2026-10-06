package tn.steg.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A community write ban (T08 / D7). While {@code mutedUntil} is in the
 * future the author cannot create posts or comments. The moderator
 * identity ({@code mutedBy}) never leaves the server — the muted student
 * sees the reason and the expiry only.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_mutes")
public class CommunityMute extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "muted_by", nullable = false)
    private User mutedBy;

    @Column(name = "muted_until", nullable = false)
    private Instant mutedUntil;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    public CommunityMute(User user, User mutedBy, Instant mutedUntil, String reason) {
        this.user = user;
        this.mutedBy = mutedBy;
        this.mutedUntil = mutedUntil;
        this.reason = reason;
    }

    public boolean isActive(Instant now) {
        return mutedUntil != null && mutedUntil.isAfter(now);
    }
}
