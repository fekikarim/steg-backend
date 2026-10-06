package tn.steg.backend.companion.application.dto;

import java.util.List;
import java.util.UUID;

/** T03 undo answer: per-item {@code REVERTED} / {@code SKIPPED_CHANGED} / {@code NOT_FOUND}. */
public record UndoApplyBatchResponse(
        UUID batchId,
        List<ApplyTaskCategoryItemResult> items
) {
}
