package tn.steg.backend.companion.application.dto;

import java.util.List;

/**
 * S6c — atomic bulk result with a per-item summary. Every mutation in the
 * request produces exactly one entry in {@code items} (same order, {@code index}
 * echoes the request position); {@code tasks} carries the created/updated task
 * bodies and {@code deletedCount} the number of deletions.
 */
public record BulkTaskResponse(
        List<TaskResponse> tasks,
        int deletedCount,
        List<BulkTaskItemResult> items
) {
}