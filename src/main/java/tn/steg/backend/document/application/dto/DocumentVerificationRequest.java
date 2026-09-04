package tn.steg.backend.document.application.dto;

import jakarta.validation.constraints.NotNull;
import tn.steg.backend.document.domain.model.DocumentVerificationStatus;

public record DocumentVerificationRequest(
        @NotNull(message = "Verification status is required")
        DocumentVerificationStatus status,
        String comment
) {}
