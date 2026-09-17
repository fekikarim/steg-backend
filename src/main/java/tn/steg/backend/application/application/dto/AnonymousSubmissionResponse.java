package tn.steg.backend.application.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Acknowledgement returned to an anonymous candidate after submission")
public record AnonymousSubmissionResponse(

        @Schema(description = "Server-generated reference e.g. APP-2025-00001")
        String reference,

        @Schema(description = "Current application status")
        String status,

        @Schema(description = "Opaque tracking token. Shown once; the only credential needed to track without an account.")
        String trackingToken
) {}
