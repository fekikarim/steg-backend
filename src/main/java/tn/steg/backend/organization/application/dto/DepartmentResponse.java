package tn.steg.backend.organization.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.organization.domain.model.Department;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Department details")
public record DepartmentResponse(

        @Schema(description = "Department UUID")
        UUID id,

        @Schema(description = "Short unique code")
        String code,

        @Schema(description = "Full name")
        String name,

        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Whether the department is active")
        Boolean active,

        @Schema(description = "Parent department UUID, null if top-level")
        UUID parentDepartmentId,

        @Schema(description = "Record creation timestamp")
        Instant createdAt,

        @Schema(description = "Last modification timestamp")
        Instant updatedAt,

        @Schema(description = "Optimistic-lock version")
        Long version
) {
    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(
                d.getId(),
                d.getCode(),
                d.getName(),
                d.getDescription(),
                d.getActive(),
                d.getParentDepartment() != null ? d.getParentDepartment().getId() : null,
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getVersion()
        );
    }
}
