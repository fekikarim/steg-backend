package tn.steg.backend.internship.application.dto;

import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record SupervisorResponse(
        UUID id,
        String email,
        String role,
        UserStatus status,
        Boolean enabled,
        int activeAssignmentsCount,
        Instant createdAt
) {
    public static SupervisorResponse from(User user, String role, int activeAssignmentsCount) {
        return new SupervisorResponse(
                user.getId(),
                user.getEmail(),
                role,
                user.getStatus(),
                user.getEnabled(),
                activeAssignmentsCount,
                user.getCreatedAt()
        );
    }
}
