package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/**
 * T03 one apply line: either {@code categoryId} (existing) or
 * {@code newCategoryName} (validated like a manual one, created on accept).
 * {@code expectedCategoryId} (null = still unclassified) is the
 * compare-and-set guard: a task classified or deleted in the meantime is
 * reported per item and never overwritten.
 */
public record ApplyTaskCategoryItem(
        UUID taskId,
        UUID categoryId,
        String newCategoryName,
        UUID expectedCategoryId
) {
}
