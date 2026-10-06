package tn.steg.backend.companion.application.dto;

import java.util.List;
import java.util.UUID;

/** T03 apply answer: per-item results plus the batch id used for undo. */
public record ApplyTaskCategoriesResponse(
        UUID batchId,
        List<ApplyTaskCategoryItemResult> items
) {
}
