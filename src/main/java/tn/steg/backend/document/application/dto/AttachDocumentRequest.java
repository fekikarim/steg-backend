package tn.steg.backend.document.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AttachDocumentRequest(
        @NotNull(message = "Document ID is required")
        UUID documentId,
        Boolean mandatory
) {}
