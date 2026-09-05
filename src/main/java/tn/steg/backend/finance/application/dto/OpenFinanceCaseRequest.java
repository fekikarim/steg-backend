package tn.steg.backend.finance.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OpenFinanceCaseRequest(
        @NotNull(message = "internshipId must not be null")
        UUID internshipId
) {
}
