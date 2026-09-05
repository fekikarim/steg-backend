package tn.steg.backend.finance.application.dto;

import tn.steg.backend.document.domain.model.DocumentVerificationStatus;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;

import java.time.Instant;
import java.util.UUID;

public record FinanceCaseDocumentResponse(
        UUID documentId,
        String documentReference,
        DocumentType documentType,
        boolean mandatory,
        DocumentVerificationStatus verificationStatus,
        String verificationComment,
        Instant reviewedAt,
        String reviewedByName
) {
    public static FinanceCaseDocumentResponse from(FinanceCaseDocument link) {
        String reviewer = link.getReviewedBy() == null ? null
                : link.getReviewedBy().getFirstName() + " " + link.getReviewedBy().getLastName();
        return new FinanceCaseDocumentResponse(
                link.getDocument().getId(),
                link.getDocument().getReference(),
                link.getDocument().getType(),
                Boolean.TRUE.equals(link.getMandatory()),
                link.getVerificationStatus(),
                link.getVerificationComment(),
                link.getReviewedAt(),
                reviewer);
    }
}
