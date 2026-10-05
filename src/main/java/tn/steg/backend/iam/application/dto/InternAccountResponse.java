package tn.steg.backend.iam.application.dto;

import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InternAccountResponse(
        UUID id,
        String email,
        List<String> roles,
        UserStatus status,
        Boolean enabled,
        Boolean mustChangePassword,
        String fullName,
        UUID candidateId,
        UUID internshipId,
        Instant createdAt
) {
    public static InternAccountResponse from(User user, String fullName, UUID candidateId, UUID internshipId) {
        List<String> roles = user.getAssignedRoles().stream()
                .map(r -> r.getCode())
                .sorted()
                .toList();
        return new InternAccountResponse(
                user.getId(),
                user.getEmail(),
                roles,
                user.getStatus(),
                user.getEnabled(),
                user.getMustChangePassword(),
                fullName,
                candidateId,
                internshipId,
                user.getCreatedAt()
        );
    }
}
