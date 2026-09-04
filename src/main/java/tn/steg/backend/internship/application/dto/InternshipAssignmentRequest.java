package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload to assign or reassign an internship to a Department and Supervisor")
public record InternshipAssignmentRequest(
        @NotNull(message = "departmentId is required")
        UUID departmentId,

        @NotNull(message = "supervisorId is required")
        UUID supervisorId,

        LocalDate startDate,

        LocalDate endDate,

        String assignmentReason
) {}
