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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted advisory python-ai verification run (AGENTS.md §7.3).
 * Advisory only: a run never changes any status by itself — the Admin's manual
 * per-document decision does. Degraded runs (service down/unconfigured) are
 * persisted too, with {@code degraded=true} and an INCONCLUSIVE overall, so the
 * UI can show "AI unavailable, validate manually" instead of failing.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "validation_verification_runs")
public class VerificationRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private ValidationDocumentType documentType;

    @Column(name = "deliverable_id")
    private UUID deliverableId;

    @Column(name = "file_asset_id")
    private UUID fileAssetId;

    @Column(name = "document_version")
    private Integer documentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_by")
    private User runBy;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "overall", nullable = false, length = 20)
    private String overall;

    @Column(name = "degraded", nullable = false)
    private boolean degraded;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    public VerificationRun(Internship internship, ValidationDocumentType documentType,
                           UUID deliverableId, UUID fileAssetId, Integer documentVersion,
                           User runBy, String overall, boolean degraded, String resultJson) {
        this.internship = internship;
        this.documentType = documentType;
        this.deliverableId = deliverableId;
        this.fileAssetId = fileAssetId;
        this.documentVersion = documentVersion;
        this.runBy = runBy;
        this.runAt = Instant.now();
        this.overall = overall;
        this.degraded = degraded;
        this.resultJson = resultJson;
    }
}
