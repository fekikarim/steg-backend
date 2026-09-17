package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe view of a logbook lifecycle row: exposes only non-sensitive fields.
 * Never carries the intern's personal data, CIN hash, or user credentials.
 */
public record LogbookResponse(
        UUID id,
        UUID internshipId,
        LogbookStatus status,
        String draftText,
        String finalText,
        UUID submittedById,
        Instant submittedAt,
        UUID validatedById,
        Instant validatedAt,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static LogbookResponse from(Logbook logbook) {
        return new LogbookResponse(
                logbook.getId(),
                logbook.getInternship() != null ? logbook.getInternship().getId() : null,
                logbook.getStatus(),
                logbook.getDraftText(),
                logbook.getFinalText(),
                logbook.getSubmittedBy() != null ? logbook.getSubmittedBy().getId() : null,
                logbook.getSubmittedAt(),
                logbook.getValidatedBy() != null ? logbook.getValidatedBy().getId() : null,
                logbook.getValidatedAt(),
                logbook.getRejectionReason(),
                logbook.getCreatedAt(),
                logbook.getUpdatedAt()
        );
    }
}