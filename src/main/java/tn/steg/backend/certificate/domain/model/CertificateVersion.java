package tn.steg.backend.certificate.domain.model;

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

import java.time.Instant;

/**
 * One issued certificate PDF (S8, AGENTS.md §5.7). Append-only: every
 * regeneration appends a row with a bumped version number — an issued PDF is
 * never overwritten, so reprints of older versions stay possible.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "certificate_versions")
public class CertificateVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certificate_id", nullable = false)
    private Certificate certificate;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset pdfFile;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    public CertificateVersion(Certificate certificate, Integer versionNumber, FileAsset pdfFile) {
        this.certificate = certificate;
        this.versionNumber = versionNumber;
        this.pdfFile = pdfFile;
        this.generatedAt = Instant.now();
    }
}
