package tn.steg.backend.finance.application.dto;

import jakarta.validation.constraints.NotNull;
import tn.steg.backend.document.domain.model.DocumentVerificationStatus;

public record ReviewFinanceDocumentRequest(
        @NotNull(message = "status must not be null")
        DocumentVerificationStatus status,
        String comment
) {
}
