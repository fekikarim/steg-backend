package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@Schema(description = "Identifiers to check for existing application — all fields optional but at least one must be provided")
public record IdentifierValidationRequest(

        @Email(message = "Invalid email address")
        @Size(max = 254, message = "Email too long")
        @Schema(description = "Candidate email address", example = "ahmed.benali@example.com")
        String email,

        @Size(min = 5, max = 20, message = "National ID must be 5-20 characters")
        @Schema(description = "National ID / CIN", example = "12345678")
        String nationalId,

        @Size(min = 8, max = 20, message = "Phone must be 8-20 characters")
        @Schema(description = "Phone number", example = "+216 98 123 456")
        String phone
) {}
