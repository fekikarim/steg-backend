package tn.steg.backend.companion.application.dto;

import java.util.List;

/** Result of an atomic draft bulk-add: one entry per draft-student pair. */
public record BulkAddDraftsResponse(
        List<DraftBulkItemResult> items
) {
}
