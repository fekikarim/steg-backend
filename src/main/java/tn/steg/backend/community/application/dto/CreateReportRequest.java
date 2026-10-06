package tn.steg.backend.community.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.steg.backend.community.domain.model.CommunityReportTarget;

import java.util.UUID;

/**
 * Payload for reporting a post or a comment (T08 / D7). Exactly one of
 * {@code postId} / {@code commentId} must be set and must agree with
 * {@code targetType}.
 */
public record CreateReportRequest(
        @NotNull(message = "Report target type is required")
        CommunityReportTarget targetType,

        UUID postId,

        UUID commentId,

        @NotBlank(message = "Report reason must not be blank")
        @Size(max = 500, message = "Report reason must not exceed 500 characters")
        String reason
) {}
