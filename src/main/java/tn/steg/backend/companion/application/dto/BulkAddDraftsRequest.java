package tn.steg.backend.companion.application.dto;

import java.util.List;
import java.util.UUID;

/** Atomic bulk-add of approved drafts to one or more students. */
public record BulkAddDraftsRequest(
        List<UUID> draftIds,
        List<UUID> internshipIds
) {
}
