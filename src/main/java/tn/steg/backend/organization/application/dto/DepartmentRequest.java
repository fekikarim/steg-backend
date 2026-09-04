package tn.steg.backend.organization.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload for creating or updating a department")
public record DepartmentRequest(

        @NotBlank(message = "Code is required")
        @Size(max = 50, message = "Code must not exceed 50 characters")
        @Schema(description = "Unique short code for the department", example = "IT-DEV")
        String code,

        @NotBlank(message = "Name is required")
        @Schema(description = "Full display name of the department", example = "IT Development")
        String name,

        @Schema(description = "Optional description", example = "Handles all software development projects")
        String description,

        @Schema(description = "UUID of the parent department for hierarchical structure")
        UUID parentDepartmentId
) {}
