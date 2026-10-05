package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.TaskDraft;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Read model for one S10a AI task draft (a proposal, not a real task). */
public record TaskDraftResponse(
        UUID id,
        UUID referenceInternshipId,
        String title,
        String description,
        LocalDate dueDate,
        Instant createdAt
) {
    public static TaskDraftResponse from(TaskDraft draft) {
        return new TaskDraftResponse(
                draft.getId(),
                draft.getReferenceInternship() != null
                        ? draft.getReferenceInternship().getId() : null,
                draft.getTitle(),
                draft.getDescription(),
                draft.getDueDate(),
                draft.getCreatedAt());
    }
}
