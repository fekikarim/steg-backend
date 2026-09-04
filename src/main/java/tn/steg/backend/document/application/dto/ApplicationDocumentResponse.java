package tn.steg.backend.document.application.dto;

import tn.steg.backend.document.domain.model.ApplicationDocument;
import tn.steg.backend.document.domain.model.DocumentVerificationStatus;

import java.time.Instant;
import java.util.UUID;

public record ApplicationDocumentResponse(
        UUID id,
        UUID applicationId,
        DocumentResponse document,
        Boolean mandatory,
        DocumentVerificationStatus verificationStatus,
        String verificationComment,
        UUID verifiedById,
        Instant verifiedAt,
        Instant createdAt
) {
    public static ApplicationDocumentResponse from(ApplicationDocument appDoc, DocumentResponse docResponse) {
        return new ApplicationDocumentResponse(
                appDoc.getId(),
                appDoc.getApplication().getId(),
                docResponse,
                appDoc.getMandatory(),
                appDoc.getVerificationStatus(),
                appDoc.getVerificationComment(),
                appDoc.getVerifiedBy() != null ? appDoc.getVerifiedBy().getId() : null,
                appDoc.getVerifiedAt(),
                appDoc.getCreatedAt()
        );
    }
}
