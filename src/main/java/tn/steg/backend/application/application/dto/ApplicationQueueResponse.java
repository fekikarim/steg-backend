package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Row of the staff application queue (AGENTS.md §5.2).
 *
 * <p>Carries the candidate identity and university in the same query result so
 * the list screen never needs a second round-trip per row.
 */
@Schema(description = "Application queue row for the Back Office (Admin: all, Supervisor: own candidates)")
public record ApplicationQueueResponse(

        UUID id,
        String reference,
        ApplicationStatus status,
        UUID candidateId,
        String candidateName,
        String candidateEmail,
        String universityName,
        Boolean submittedOnline,
        LocalDate submissionDate,
        InternshipType calculatedType,
        InternshipRequirement requirement,
        String rejectionReason,
        String correctionComment,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static ApplicationQueueResponse from(InternshipApplication a) {
        String candidateName = a.getCandidate() != null
                ? a.getCandidate().getFirstName() + " " + a.getCandidate().getLastName()
                : null;
        String universityName = a.getCandidate() != null && a.getCandidate().getUniversity() != null
                ? a.getCandidate().getUniversity().getName()
                : null;

        return new ApplicationQueueResponse(
                a.getId(),
                a.getReference(),
                a.getStatus(),
                a.getCandidate() != null ? a.getCandidate().getId() : null,
                candidateName,
                a.getCandidate() != null ? a.getCandidate().getEmail() : null,
                universityName,
                a.getSubmittedOnline(),
                a.getSubmissionDate(),
                a.getCalculatedType(),
                a.getRequirement(),
                a.getRejectionReason(),
                a.getCorrectionComment(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getVersion()
        );
    }
}
