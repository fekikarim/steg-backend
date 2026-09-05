package tn.steg.backend.messaging.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Payload for creating a GROUP conversation.
 * Membership is scoped to users who are currently interns (ACTIVE internship);
 * staff/moderator callers are validated server-side.
 */
public record CreateGroupRequest(
        @NotBlank(message = "Group title must not be blank")
        @Size(max = 255, message = "Group title must not exceed 255 characters")
        String title,
        List<UUID> memberUserIds
) {}
