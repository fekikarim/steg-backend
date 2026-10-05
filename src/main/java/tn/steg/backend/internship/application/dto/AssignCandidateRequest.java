package tn.steg.backend.internship.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignCandidateRequest(
        @NotNull UUID internshipId,
        UUID departmentId
) {
}
