package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "Anonymous candidate application submission payload")
public record AnonymousApplicationSubmitRequest(

        @NotBlank(message = "First name is required")
        String firstName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotBlank(message = "Email is required")
        String email,

        String phone,

        LocalDate birthDate,

        @NotBlank(message = "National ID (CIN) is required")
        String nationalId,

        @NotNull(message = "University is required")
        java.util.UUID universityId,

        String speciality,

        String diploma,

        @NotNull(message = "Desired start date is required")
        LocalDate desiredStartDate,

        @NotNull(message = "Desired end date is required")
        LocalDate desiredEndDate
) {}
