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
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "file_assets")
public class FileAsset extends BaseEntity {

    @Column(name = "storage_provider", nullable = false, length = 50)
    private String storageProvider = "local";

    @Column(name = "bucket", length = 100)
    private String bucket;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "checksum", nullable = false, length = 128)
    private String checksum;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long size;

    @Column(name = "encrypted_at_rest", nullable = false)
    private Boolean encryptedAtRest = false;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private User uploadedBy;

    public FileAsset(String storageKey, String originalFileName, String checksum, String mimeType, Long size, User uploadedBy) {
        this.storageKey = storageKey;
        this.originalFileName = originalFileName;
        this.checksum = checksum;
        this.mimeType = mimeType;
        this.size = size;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
    }
}
