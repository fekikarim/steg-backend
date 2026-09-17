package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Opaque tracking token presented without an account")
public record PublicApplicationTrackRequest(
        @NotBlank(message = "Tracking token is required")
        String trackingToken
) {}
