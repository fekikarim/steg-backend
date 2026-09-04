package tn.steg.backend.common.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encapsulates field-level validation errors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Details regarding an invalid request parameter or field")
public class FieldErrorDto {

    @Schema(description = "Field name", example = "email")
    private String field;

    @Schema(description = "Rejected input value", example = "invalid-email")
    private Object rejectedValue;

    @Schema(description = "Validation failure explanation", example = "must be a well-formed email address")
    private String message;
}
