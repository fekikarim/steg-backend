package tn.steg.backend.community.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for commenting on a community post (T08 / ST-COM-01).
 */
public record CreateCommentRequest(
        @NotBlank(message = "Comment content must not be blank")
        @Size(max = 1000, message = "Comment content must not exceed 1000 characters")
        String body
) {}
