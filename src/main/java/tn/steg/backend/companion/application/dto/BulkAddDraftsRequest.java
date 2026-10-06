package tn.steg.backend.companion.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Atomic bulk-add of approved drafts to one or more students. */
public record BulkAddDraftsRequest(
        List<UUID> draftIds,
        List<UUID> internshipIds,
        /**
         * T05/D8 optional batch schedule: applied to every created task.
         * Null = immediate. Validated against each target's own internship
         * period exactly like a supervisor-scheduled task (T04 rule); a
         * past instant is immediate, never an error.
         */
        Instant visibleFrom
) {
    /** Pre-T05 shape (no scheduling requested). */
    public BulkAddDraftsRequest(List<UUID> draftIds, List<UUID> internshipIds) {
        this(draftIds, internshipIds, null);
    }
}
