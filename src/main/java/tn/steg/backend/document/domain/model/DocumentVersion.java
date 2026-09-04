package tn.steg.backend.document.domain.model;

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

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_versions")
public class DocumentVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset file;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "checksum", nullable = false, length = 128)
    private String checksum;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public DocumentVersion(Document document, FileAsset file, Integer versionNumber, String originalFileName, String checksum) {
        this.document = document;
        this.file = file;
        this.versionNumber = versionNumber;
        this.originalFileName = originalFileName;
        this.checksum = checksum;
        this.uploadedAt = Instant.now();
    }
}
