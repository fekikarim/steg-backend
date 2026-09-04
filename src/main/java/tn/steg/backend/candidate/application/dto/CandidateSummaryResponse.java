package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.candidate.domain.model.Candidate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Safe list/summary view — nationalId (CIN) is intentionally omitted.
 */
@Schema(description = "Candidate summary (nationalId is never included in list responses)")
public record CandidateSummaryResponse(

        @Schema(description = "Candidate UUID")
        UUID id,

        @Schema(description = "First name")
        String firstName,

        @Schema(description = "Last name")
        String lastName,

        @Schema(description = "Email")
        String email,

        @Schema(description = "Phone number")
        String phone,

        @Schema(description = "Date of birth")
        LocalDate birthDate,

        @Schema(description = "Speciality / field of study")
        String speciality,

        @Schema(description = "Diploma / degree")
        String diploma,

        @Schema(description = "University UUID")
        UUID universityId,

        @Schema(description = "University name")
        String universityName,

        @Schema(description = "Linked user account UUID")
        UUID userId,

        @Schema(description = "Record creation timestamp")
        Instant createdAt,

        @Schema(description = "Last modification timestamp")
        Instant updatedAt,

        @Schema(description = "Optimistic-lock version")
        Long version
) {
    public static CandidateSummaryResponse from(Candidate c) {
        return new CandidateSummaryResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getBirthDate(),
                c.getSpeciality(),
                c.getDiploma(),
                c.getUniversity() != null ? c.getUniversity().getId() : null,
                c.getUniversity() != null ? c.getUniversity().getName() : null,
                c.getUser() != null ? c.getUser().getId() : null,
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getVersion()
        );
    }
}
