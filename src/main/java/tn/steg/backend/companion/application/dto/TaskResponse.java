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
        Instant createdAt,
        Instant updatedAt
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
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
