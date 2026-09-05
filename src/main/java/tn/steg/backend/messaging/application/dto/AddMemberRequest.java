package tn.steg.backend.messaging.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Payload for adding a member to a GROUP conversation.
 */
public record AddMemberRequest(
        @NotNull(message = "userId must not be null")
        UUID userId
) {}
