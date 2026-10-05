package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload for creating or updating a candidate profile")
public record CandidateRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        @Schema(description = "First name", example = "Ahmed")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        @Schema(description = "Last name", example = "Ben Ali")
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Schema(description = "Contact email", example = "ahmed.benali@example.com")
        String email,

        @Schema(description = "Phone number")
        String phone,

        @Schema(description = "Date of birth")
        LocalDate birthDate,

        @Schema(description = "Home address")
        String address,

        @Schema(description = "Field of speciality", example = "Computer Science")
        String speciality,

        @Schema(description = "Diploma or degree obtained", example = "Licence en Informatique")
        String diploma,

        @Schema(description = "Comma-separated or freetext list of skills")
        String skills,

        @Schema(description = "Languages spoken")
        String languages,

        @NotNull(message = "University ID is required")
        @Schema(description = "UUID of the candidate's university")
        UUID universityId,

        @Schema(description = "National ID (CIN) — required on create; when omitted on update the stored "
                + "value is kept (staff and supervisors edit without ever receiving the CIN)",
                example = "12345678")
        String nationalId
) {}
