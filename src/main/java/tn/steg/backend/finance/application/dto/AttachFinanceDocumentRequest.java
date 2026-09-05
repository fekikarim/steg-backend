package tn.steg.backend.finance.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AttachFinanceDocumentRequest(
        @NotNull(message = "documentId must not be null")
        UUID documentId,
        Boolean mandatory
) {
}
