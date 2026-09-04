package tn.steg.backend.organization.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload for creating or updating an employee")
public record EmployeeRequest(

        @NotBlank(message = "Employee number is required")
        @Size(max = 50, message = "Employee number must not exceed 50 characters")
        @Schema(description = "Unique employee number", example = "EMP-00123")
        String employeeNumber,

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        @Schema(description = "First name", example = "Karim")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        @Schema(description = "Last name", example = "Feki")
        String lastName,

        @Size(max = 50)
        @Schema(description = "Phone number")
        String phoneNumber,

        @Size(max = 100)
        @Schema(description = "Job title / position", example = "Software Engineer")
        String position,

        @Schema(description = "Date of hiring", example = "2024-01-15")
        LocalDate hireDate,

        @NotNull(message = "Department ID is required")
        @Schema(description = "UUID of the department this employee belongs to")
        UUID departmentId,

        @Schema(description = "UUID of the linked user account (optional)")
        UUID userId
) {}
