package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.candidate.domain.model.Candidate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Full detail view including decrypted nationalId.
 * Only returned to the candidate themselves or ADMIN / HR staff.
 */
@Schema(description = "Full candidate profile including sensitive fields (only for self or staff)")
public record CandidateDetailResponse(

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

        @Schema(description = "Home address")
        String address,

        @Schema(description = "Speciality")
        String speciality,

        @Schema(description = "Diploma")
        String diploma,

        @Schema(description = "Skills")
        String skills,

        @Schema(description = "Languages")
        String languages,

        @Schema(description = "National ID (CIN) — decrypted, only shown to self or staff")
        String nationalId,

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
    /**
     * Build a detail response, optionally including the decrypted national ID.
     *
     * @param c           the candidate entity
     * @param nationalId  decrypted nationalId or null when redacted
     */
    public static CandidateDetailResponse from(Candidate c, String nationalId) {
        return new CandidateDetailResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getBirthDate(),
                c.getAddress(),
                c.getSpeciality(),
                c.getDiploma(),
                c.getSkills(),
                c.getLanguages(),
                nationalId,
                c.getUniversity() != null ? c.getUniversity().getId() : null,
                c.getUniversity() != null ? c.getUniversity().getName() : null,
                c.getUser() != null ? c.getUser().getId() : null,
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getVersion()
        );
    }
}
