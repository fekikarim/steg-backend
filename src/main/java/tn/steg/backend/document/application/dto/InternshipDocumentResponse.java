package tn.steg.backend.document.application.dto;

import tn.steg.backend.document.domain.model.InternshipDocument;

import java.time.Instant;
import java.util.UUID;

public record InternshipDocumentResponse(
        UUID id,
        UUID internshipId,
        DocumentResponse document,
        Boolean mandatory,
        Boolean generatedAutomatically,
        Instant createdAt
) {
    public static InternshipDocumentResponse from(InternshipDocument internDoc, DocumentResponse docResponse) {
        return new InternshipDocumentResponse(
                internDoc.getId(),
                internDoc.getInternship().getId(),
                docResponse,
                internDoc.getMandatory(),
                internDoc.getGeneratedAutomatically(),
                internDoc.getCreatedAt()
        );
    }
}
