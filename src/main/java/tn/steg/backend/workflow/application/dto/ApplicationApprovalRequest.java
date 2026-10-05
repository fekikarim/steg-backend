package tn.steg.backend.workflow.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ApplicationApprovalRequest(
        UUID supervisorUserId,
        @NotNull UUID departmentId
) {
}