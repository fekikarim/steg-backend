package tn.steg.backend.internship.domain.model;

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
 * One mandatory manual Admin decision on one validation document (AGENTS.md
 * §5.11 step 4). Immutable history: the <em>current</em> decision per document
 * is the latest row. UNDER_VALIDATION becomes VALIDATED only when the latest
 * decision for BOTH documents is VALIDATED; a REJECTED decision (comment
 * mandatory) returns the internship to REPORT_SUBMITTED for resubmission
 * (audit assumption #18).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "validation_decisions")
public class DocumentValidationDecision extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private ValidationDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private ValidationDecision decision;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public DocumentValidationDecision(Internship internship, ValidationDocumentType documentType,
                                      ValidationDecision decision, String comment, User decidedBy) {
        this.internship = internship;
        this.documentType = documentType;
        this.decision = decision;
        this.comment = comment;
        this.decidedBy = decidedBy;
        this.decidedAt = Instant.now();
    }
}
