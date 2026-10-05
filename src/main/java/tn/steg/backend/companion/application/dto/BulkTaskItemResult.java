package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/**
 * S6c — one entry of the bulk per-item result summary. The bulk endpoint is
 * atomic (a failing item rolls back ALL items and the response is an error,
 * never a partial success), so every entry in a success response has status
 * {@code OK}; the list proves which item produced which task.
 */
public record BulkTaskItemResult(
        int index,
        BulkTaskAction action,
        UUID taskId,
        UUID internshipId,
        String status
) {
}
