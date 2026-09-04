package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Internship application details")
public record ApplicationResponse(

        @Schema(description = "Application UUID")
        UUID id,

        @Schema(description = "Server-generated reference e.g. APP-2025-00001")
        String reference,

        @Schema(description = "Current status in the lifecycle state machine")
        ApplicationStatus status,

        @Schema(description = "Candidate UUID")
        UUID candidateId,

        @Schema(description = "Candidate full name")
        String candidateName,

        @Schema(description = "Desired start date")
        LocalDate desiredStartDate,

        @Schema(description = "Desired end date")
        LocalDate desiredEndDate,

        @Schema(description = "Proposed internship theme")
        String proposedTheme,

        @Schema(description = "Whether submitted online")
        Boolean submittedOnline,

        @Schema(description = "Date the application was formally submitted")
        LocalDate submissionDate,

        @Schema(description = "Calculated internship type (set by business logic)")
        InternshipType calculatedType,

        @Schema(description = "Academic requirement category")
        InternshipRequirement requirement,

        @Schema(description = "Rejection reason (only present when REJECTED)")
        String rejectionReason,

        @Schema(description = "Correction comment (only present when NEEDS_CORRECTION)")
        String correctionComment,

        @Schema(description = "Reviewer employee UUID")
        UUID reviewerId,

        @Schema(description = "Record creation timestamp")
        Instant createdAt,

        @Schema(description = "Last modification timestamp")
        Instant updatedAt,

        @Schema(description = "Optimistic-lock version")
        Long version
) {
    public static ApplicationResponse from(InternshipApplication a) {
        String candidateName = a.getCandidate() != null
                ? a.getCandidate().getFirstName() + " " + a.getCandidate().getLastName()
                : null;

        return new ApplicationResponse(
                a.getId(),
                a.getReference(),
                a.getStatus(),
                a.getCandidate() != null ? a.getCandidate().getId() : null,
                candidateName,
                a.getDesiredStartDate(),
                a.getDesiredEndDate(),
                a.getProposedTheme(),
                a.getSubmittedOnline(),
                a.getSubmissionDate(),
                a.getCalculatedType(),
                a.getRequirement(),
                a.getRejectionReason(),
                a.getCorrectionComment(),
                a.getReviewer() != null ? a.getReviewer().getId() : null,
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getVersion()
        );
    }
}
