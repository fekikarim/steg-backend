package tn.steg.backend.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateInternAccountRequest(
        @NotBlank @Email String email,
        @NotBlank String role,
        UUID candidateId,
        String fullName
) {
}
