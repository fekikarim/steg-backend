package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.TaskCategory;

import java.time.Instant;
import java.util.UUID;

/** Read model for one T03 student-defined task category. */
public record TaskCategoryResponse(
        UUID id,
        String name,
        String color,
        int position,
        Instant createdAt
) {
    public static TaskCategoryResponse from(TaskCategory category) {
        return new TaskCategoryResponse(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.getPosition(),
                category.getCreatedAt());
    }
}
