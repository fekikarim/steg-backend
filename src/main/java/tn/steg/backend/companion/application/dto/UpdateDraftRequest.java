package tn.steg.backend.companion.application.dto;

import java.time.LocalDate;

/** Manual draft edit (lengths and period re-validated server-side). */
public record UpdateDraftRequest(
        String title,
        String description,
        LocalDate dueDate
) {
}
