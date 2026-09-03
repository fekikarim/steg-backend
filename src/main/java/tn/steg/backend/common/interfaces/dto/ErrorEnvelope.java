package tn.steg.backend.common.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard consistent error envelope across all REST APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard API error response envelope")
public class ErrorEnvelope {

    @Schema(description = "UTC timestamp of the error event", example = "2026-09-03T14:30:00Z")
    private Instant timestamp;

    @Schema(description = "HTTP status code", example = "400")
    private Integer status;

    @Schema(description = "HTTP error title or error code", example = "Bad Request")
    private String error;

    @Schema(description = "Human-readable error description", example = "The requested business operation cannot be completed.")
    private String message;

    @Schema(description = "Request URI path", example = "/api/applications")
    private String path;

    @Schema(description = "Correlation trace identifier for log tracing", example = "a1b2c3d4-e5f6-7890")
    private String traceId;

    @Builder.Default
    @Schema(description = "Field-level validation error breakdown")
    private List<FieldErrorDto> fieldErrors = new ArrayList<>();
}
