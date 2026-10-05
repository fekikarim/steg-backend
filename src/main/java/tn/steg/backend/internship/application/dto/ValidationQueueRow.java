package tn.steg.backend.internship.application.dto;

import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One S7 validation queue row: the internship plus the candidate/supervisor
 * data the Admin filters and sorts on (§5.11 step 1).
 */
public record ValidationQueueRow(
        UUID internshipId,
        String reference,
        InternshipStatus status,
        InternshipType type,
        LocalDate startDate,
        LocalDate endDate,
        UUID candidateId,
        String candidateName,
        String candidateEmail,
        String universityName,
        UUID supervisorUserId,
        String supervisorEmail
) {
    public static ValidationQueueRow from(Internship internship) {
        var candidate = internship.getCandidate();
        String candidateName = candidate != null
                ? (candidate.getFirstName() + " " + candidate.getLastName()).strip() : "—";
        return new ValidationQueueRow(
                internship.getId(),
                internship.getReference(),
                internship.getStatus(),
                internship.getType(),
                internship.getStartDate(),
                internship.getEndDate(),
                candidate != null ? candidate.getId() : null,
                candidateName,
                candidate != null ? candidate.getEmail() : null,
                candidate != null && candidate.getUniversity() != null
                        ? candidate.getUniversity().getName() : null,
                internship.getSupervisorUser() != null ? internship.getSupervisorUser().getId() : null,
                internship.getSupervisorUser() != null ? internship.getSupervisorUser().getEmail() : null);
    }
}
