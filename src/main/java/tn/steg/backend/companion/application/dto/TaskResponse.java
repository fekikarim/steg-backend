package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID internshipId,
        UUID createdById,
        String createdByEmail,
        UUID assignedToId,
        String assignedToEmail,
        String title,
        String description,
        TaskStatus status,
        LocalDate dueDate,
        Instant completedAt,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt,
        /**
         * T04/D8 scheduled visibility (null = immediate). Staff-authored
         * scheduling metadata, not T03-private: it feeds the supervisor
         * "scheduled" badge and the student's own (already visible) tasks.
         * Hidden tasks are never served to interns in the first place.
         */
        Instant visibleFrom
) {
    public static TaskResponse from(Task task) {
        String createdByEmail = task.getCreatedBy() != null ? task.getCreatedBy().getEmail() : null;
        UUID assignedToId = task.getAssignedTo() != null ? task.getAssignedTo().getId() : null;
        String assignedToEmail = task.getAssignedTo() != null ? task.getAssignedTo().getEmail() : null;

        return new TaskResponse(
                task.getId(),
                task.getInternship() != null ? task.getInternship().getId() : null,
                task.getCreatedBy() != null ? task.getCreatedBy().getId() : null,
                createdByEmail,
                assignedToId,
                assignedToEmail,
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getDueDate(),
                task.getCompletedAt(),
                task.getReviewReason(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getVisibleFrom()
        );
    }
}
