package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Internship details representation")
public record InternshipResponse(
        UUID id,
        String reference,
        LocalDate startDate,
        LocalDate endDate,
        InternshipStatus status,
        InternshipType type,
        InternshipRequirement requirement,
        boolean paymentEligible,
        String subject,
        String academicLevel,
        UUID candidateId,
        String candidateFullName,
        UUID applicationId,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static InternshipResponse from(Internship internship) {
        String candidateName = internship.getCandidate() != null
                ? internship.getCandidate().getFirstName() + " " + internship.getCandidate().getLastName()
                : null;
        UUID candId = internship.getCandidate() != null ? internship.getCandidate().getId() : null;
        UUID appId = internship.getApplication() != null ? internship.getApplication().getId() : null;
        boolean paymentEligible = internship.getRequirement() == InternshipRequirement.OBLIGATOIRE;

        return new InternshipResponse(
                internship.getId(),
                internship.getReference(),
                internship.getStartDate(),
                internship.getEndDate(),
                internship.getStatus(),
                internship.getType(),
                internship.getRequirement(),
                paymentEligible,
                internship.getSubject(),
                internship.getAcademicLevel(),
                candId,
                candidateName,
                appId,
                internship.getCreatedAt(),
                internship.getUpdatedAt(),
                internship.getVersion()
        );
    }
}
