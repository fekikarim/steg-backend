package tn.steg.backend.community.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload for muting a student from the community (T08 / D7, staff only).
 * Duration is bounded (5 minutes .. 30 days); the reason is shown to the
 * muted student, the moderator identity never is.
 */
public record MuteStudentRequest(
        @NotNull(message = "Muted user id is required")
        UUID userId,

        @NotNull(message = "Mute duration in minutes is required")
        @Min(value = 5, message = "Mute duration must be at least 5 minutes")
        @Max(value = 43200, message = "Mute duration must not exceed 30 days")
        Integer minutes,

        @NotBlank(message = "Mute reason must not be blank")
        @Size(max = 500, message = "Mute reason must not exceed 500 characters")
        String reason
) {}
