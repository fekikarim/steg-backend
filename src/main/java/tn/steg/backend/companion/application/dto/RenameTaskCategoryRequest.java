package tn.steg.backend.companion.application.dto;

/** T03 rename-category payload (same name rules as creation). */
public record RenameTaskCategoryRequest(
        String name,
        String color
) {
}
