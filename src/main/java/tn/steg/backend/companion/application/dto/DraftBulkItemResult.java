package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/** Per-pair result of a draft bulk-add, in request order. */
public record DraftBulkItemResult(
        int index,
        UUID draftId,
        UUID internshipId,
        UUID taskId,
        String status
) {
}
