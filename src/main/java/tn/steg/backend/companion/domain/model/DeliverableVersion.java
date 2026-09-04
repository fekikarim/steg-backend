package tn.steg.backend.companion.domain.model;

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
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "deliverable_versions")
public class DeliverableVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deliverable_id", nullable = false)
    private Deliverable deliverable;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private User uploadedBy;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public DeliverableVersion(Deliverable deliverable, FileAsset file, User uploadedBy, Integer versionNumber, String changeSummary) {
        this.deliverable = deliverable;
        this.file = file;
        this.uploadedBy = uploadedBy;
        this.versionNumber = versionNumber;
        this.changeSummary = changeSummary;
        this.uploadedAt = Instant.now();
    }
}
