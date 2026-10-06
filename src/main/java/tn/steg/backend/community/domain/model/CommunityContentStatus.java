package tn.steg.backend.community.domain.model;

/**
 * Lifecycle of a community post or comment (T08 / BR-39).
 *
 * <ul>
 *   <li>{@code VISIBLE} — in the feed / thread.</li>
 *   <li>{@code DELETED} — withdrawn by its own author (no reason required).</li>
 *   <li>{@code REMOVED} — taken down by a moderator (reason mandatory,
 *   audited, author notified).</li>
 * </ul>
 * Removed and deleted rows stay in the database (audit/history) but are
 * hidden from every read; single-resource reads answer 404 with a
 * distinct code so the UI can explain instead of pretending.
 */
public enum CommunityContentStatus {
    VISIBLE,
    DELETED,
    REMOVED
}
