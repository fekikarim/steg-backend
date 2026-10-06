package tn.steg.backend.companion.application.dto;

import java.util.List;

/**
 * T03 accept batch: per-item (not all-or-nothing — partial success is
 * reported per item). Send {@code X-Idempotency-Key} so a double submit
 * replays instead of duplicating.
 */
public record ApplyTaskCategoriesRequest(
        List<ApplyTaskCategoryItem> items
) {
}
