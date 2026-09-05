package tn.steg.backend.certificate.application.dto;

import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.model.CertificateStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CertificateResponse(
        UUID id,
        String reference,
        CertificateStatus status,
        String templateCode,
        int templateVersion,
        UUID internshipId,
        String internshipReference,
        Instant generatedAt,
        LocalDate issueDate
) {
    public static CertificateResponse from(Certificate certificate) {
        return new CertificateResponse(
                certificate.getId(),
                certificate.getReference(),
                certificate.getStatus(),
                certificate.getTemplateCode(),
                certificate.getTemplateVersion(),
                certificate.getInternship().getId(),
                certificate.getInternship().getReference(),
                certificate.getGeneratedAt(),
                certificate.getIssueDate());
    }
}
