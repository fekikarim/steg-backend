package tn.steg.backend.document.application.dto;

import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.DocumentVersion;
import tn.steg.backend.document.domain.model.FileAsset;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String reference,
        DocumentType type,
        Boolean restrictedAccess,
        Boolean generatedAutomatically,
        Integer latestVersionNumber,
        String originalFileName,
        String mimeType,
        Long sizeBytes,
        String checksum,
        Instant uploadedAt,
        Instant createdAt
) {
    public static DocumentResponse from(Document doc, DocumentVersion latestVersion, FileAsset fileAsset) {
        return new DocumentResponse(
                doc.getId(),
                doc.getReference(),
                doc.getType(),
                doc.getRestrictedAccess(),
                doc.getGeneratedAutomatically(),
                latestVersion != null ? latestVersion.getVersionNumber() : 1,
                latestVersion != null ? latestVersion.getOriginalFileName() : (fileAsset != null ? fileAsset.getOriginalFileName() : null),
                fileAsset != null ? fileAsset.getMimeType() : null,
                fileAsset != null ? fileAsset.getSize() : null,
                fileAsset != null ? fileAsset.getChecksum() : (latestVersion != null ? latestVersion.getChecksum() : null),
                latestVersion != null ? latestVersion.getUploadedAt() : (fileAsset != null ? fileAsset.getUploadedAt() : null),
                doc.getCreatedAt()
        );
    }
}
