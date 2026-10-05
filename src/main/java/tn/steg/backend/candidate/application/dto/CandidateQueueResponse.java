package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of the staff candidate queue (AGENTS.md §5.1).
 *
 * <p>Carries the account-validation state, the application count/latest status
 * and the current supervisor in the same row so the list screen never needs a
 * second round-trip per row. nationalId (CIN) is intentionally absent — it can
 * never leak through this endpoint.
 */
@Schema(description = "Candidate queue row for the Back Office (Admin: all, Supervisor: own candidates)")
public record CandidateQueueResponse(

        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String speciality,
        String diploma,
        UUID universityId,
        String universityName,
        UUID userId,
        Boolean accountEnabled,
        long applicationCount,
        ApplicationStatus latestApplicationStatus,
        UUID supervisorUserId,
        String supervisorEmail,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static CandidateQueueResponse from(
            Candidate c,
            long applicationCount,
            ApplicationStatus latestApplicationStatus,
            User supervisor) {
        User account = c.getUser();
        return new CandidateQueueResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getSpeciality(),
                c.getDiploma(),
                c.getUniversity() != null ? c.getUniversity().getId() : null,
                c.getUniversity() != null ? c.getUniversity().getName() : null,
                account != null ? account.getId() : null,
                account != null ? Boolean.TRUE.equals(account.getEnabled()) : null,
                applicationCount,
                latestApplicationStatus,
                supervisor != null ? supervisor.getId() : null,
                supervisor != null ? supervisor.getEmail() : null,
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getVersion()
        );
    }
}
