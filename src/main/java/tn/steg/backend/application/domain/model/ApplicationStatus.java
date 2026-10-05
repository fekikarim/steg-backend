package tn.steg.backend.application.domain.model;

/**
 * Explicit application lifecycle (AGENTS.md §4):
 * {@code SUBMITTED → MODIFICATION_REQUESTED ↔ RESUBMITTED → APPROVED | REJECTED}.
 *
 * <p>The legacy values {@code ACCEPTED} and {@code NEEDS_CORRECTION} were REMOVED
 * in S1b: they duplicated {@link #APPROVED} and {@link #MODIFICATION_REQUESTED}.
 * Existing rows were migrated by {@code V39} and re-asserted by
 * {@code V43} (audit assumption #15); no compatibility branch remains, so a
 * client still sending them gets a deserialization error instead of silently
 * landing in a legacy state.
 */
public enum ApplicationStatus {
    DRAFT,
    SUBMITTED,
    RESUBMITTED,
    UNDER_REVIEW,
    MODIFICATION_REQUESTED,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
