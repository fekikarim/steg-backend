package tn.steg.backend.messaging.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for sending a message via REST fallback or STOMP.
 * Attachments (if any) are referenced by previously uploaded FileAsset ids
 * validated through the Phase A6 storage/validation pipeline.
 */
public record SendMessageRequest(
        @NotBlank(message = "Message content must not be blank")
        @Size(max = 4000, message = "Message content must not exceed 4000 characters")
        String content
) {}
