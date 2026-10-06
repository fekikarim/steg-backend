package tn.steg.backend.community.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating a community post (T08 / ST-COM-01).
 */
public record CreatePostRequest(
        @NotBlank(message = "Post content must not be blank")
        @Size(max = 2000, message = "Post content must not exceed 2000 characters")
        String body
) {}
