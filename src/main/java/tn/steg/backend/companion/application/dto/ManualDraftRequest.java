package tn.steg.backend.companion.application.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** Manual draft creation (works even when AI is unavailable). */
public record ManualDraftRequest(
        @NotNull UUID referenceInternshipId,
        String title,
        String description,
        LocalDate dueDate
) {
}
