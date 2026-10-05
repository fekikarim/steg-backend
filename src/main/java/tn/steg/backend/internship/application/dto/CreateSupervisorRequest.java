package tn.steg.backend.internship.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateSupervisorRequest(
        @NotBlank @Email String email
) {
}
