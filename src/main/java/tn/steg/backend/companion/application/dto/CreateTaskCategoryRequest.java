package tn.steg.backend.companion.application.dto;

/** T03 create-category payload (name rules enforced server-side). */
public record CreateTaskCategoryRequest(
        String name,
        String color
) {
}
