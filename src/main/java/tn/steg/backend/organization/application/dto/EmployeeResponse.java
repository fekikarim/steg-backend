package tn.steg.backend.organization.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.organization.domain.model.Employee;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Employee details")
public record EmployeeResponse(

        @Schema(description = "Employee UUID")
        UUID id,

        @Schema(description = "Employee number")
        String employeeNumber,

        @Schema(description = "First name")
        String firstName,

        @Schema(description = "Last name")
        String lastName,

        @Schema(description = "Phone number")
        String phoneNumber,

        @Schema(description = "Position / job title")
        String position,

        @Schema(description = "Hire date")
        LocalDate hireDate,

        @Schema(description = "Whether the employee is active")
        Boolean active,

        @Schema(description = "Department UUID")
        UUID departmentId,

        @Schema(description = "Department name")
        String departmentName,

        @Schema(description = "Linked user account UUID")
        UUID userId,

        @Schema(description = "Record creation timestamp")
        Instant createdAt,

        @Schema(description = "Last modification timestamp")
        Instant updatedAt,

        @Schema(description = "Optimistic-lock version")
        Long version
) {
    public static EmployeeResponse from(Employee e) {
        return new EmployeeResponse(
                e.getId(),
                e.getEmployeeNumber(),
                e.getFirstName(),
                e.getLastName(),
                e.getPhoneNumber(),
                e.getPosition(),
                e.getHireDate(),
                e.getActive(),
                e.getDepartment() != null ? e.getDepartment().getId() : null,
                e.getDepartment() != null ? e.getDepartment().getName() : null,
                e.getUser() != null ? e.getUser().getId() : null,
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
