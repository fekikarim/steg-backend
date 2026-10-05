package tn.steg.backend.certificate.application.dto;

import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.model.CertificateVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S8 certificate detail: the current row plus the append-only version
 * history (every regeneration kept, never overwritten).
 */
public record CertificateDetailResponse(
        CertificateRowResponse certificate,
        List<VersionEntry> versions
) {
    public record VersionEntry(
            int versionNumber,
            UUID fileAssetId,
            Instant generatedAt
    ) {
    }

    public static CertificateDetailResponse from(Certificate certificate, List<CertificateVersion> versions) {
        return new CertificateDetailResponse(
                CertificateRowResponse.from(certificate),
                versions.stream()
                        .map(v -> new VersionEntry(
                                v.getVersionNumber(),
                                v.getPdfFile() != null ? v.getPdfFile().getId() : null,
                                v.getGeneratedAt()))
                        .toList());
    }
}
