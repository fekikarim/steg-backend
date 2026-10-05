package tn.steg.backend.certificate.application.dto;

import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.model.CertificateStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * S8 workspace row: one server page, no PDF bytes, no CIN — the list can
 * never leak either.
 */
public record CertificateRowResponse(
        UUID id,
        String reference,
        CertificateStatus status,
        int versionNumber,
        LocalDate issueDate,
        Instant generatedAt,
        UUID internshipId,
        String internshipReference,
        String candidateName,
        String candidateEmail,
        String universityName,
        String internshipType
) {
    public static CertificateRowResponse from(Certificate certificate) {
        var internship = certificate.getInternship();
        var candidate = internship != null ? internship.getCandidate() : null;
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).strip() : "—";
        return new CertificateRowResponse(
                certificate.getId(),
                certificate.getReference(),
                certificate.getStatus(),
                certificate.getVersionNumber() != null ? certificate.getVersionNumber() : 1,
                certificate.getIssueDate(),
                certificate.getGeneratedAt(),
                internship != null ? internship.getId() : null,
                internship != null ? internship.getReference() : null,
                candidateName,
                candidate != null ? candidate.getEmail() : null,
                candidate != null && candidate.getUniversity() != null
                        ? candidate.getUniversity().getName() : null,
                internship != null && internship.getType() != null
                        ? internship.getType().name() : null);
    }
}
