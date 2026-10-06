package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/**
 * T03 one apply result line: {@code APPLIED}, {@code SKIPPED_ALREADY_CLASSIFIED}
 * (task classified since the proposal — never overwritten),
 * {@code NOT_FOUND} (deleted or out of scope),
 * {@code INVALID_CATEGORY} (unknown, foreign or invalid name) or
 * {@code SKIPPED_CHANGED} (compare-and-set mismatch).
 */
public record ApplyTaskCategoryItemResult(
        UUID taskId,
        String status,
        UUID categoryId,
        String message
) {
}
