package tn.steg.backend.companion.domain.model;

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
import tn.steg.backend.internship.domain.model.Internship;

import java.time.Instant;
import java.util.UUID;

/**
 * Official Internship Logbook (Carnet de Stage).
 *
 * <p>Generated from AI draft based on actual recorded data, then edited by the
 * intern, submitted for supervisor validation, and finally marked official.
 *
 * <p>States: DRAFT (AI generated) → SUBMITTED (awaiting review) →
 * VALIDATED/REJECTED → OFFICIAL (final, immutable).
 *
 * <p>CIN and restricted documents are never included in the AI prompt.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "internship_logbooks")
public class Logbook extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LogbookStatus status = LogbookStatus.DRAFT;

    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

    @Column(name = "draft_text", columnDefinition = "TEXT")
    private String draftText;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private User submittedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by")
    private User validatedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    public Logbook(UUID aiAnalysisId, Internship internship, User submittedBy, String draftText) {
        this.aiAnalysisId = aiAnalysisId;
        this.internship = internship;
        this.submittedBy = submittedBy;
        this.draftText = draftText;
    }
}