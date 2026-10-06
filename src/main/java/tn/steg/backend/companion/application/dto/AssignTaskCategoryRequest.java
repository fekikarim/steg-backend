package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/**
 * T03 assign/clear payload with compare-and-set semantics: the write applies
 * only when the task still carries {@code expectedCategoryId} (null = still
 * unclassified), unless {@code force} is true after an explicit user confirm.
 * A stale expectation is refused with 409 {@code CATEGORY_CHANGED} and the
 * existing classification is never overwritten blindly.
 */
public record AssignTaskCategoryRequest(
        UUID categoryId,
        UUID expectedCategoryId,
        Boolean force
) {
}
