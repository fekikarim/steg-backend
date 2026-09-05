package tn.steg.backend.messaging.domain.model;

/**
 * Message delivery/edit lifecycle.
 *
 * <p>Reconciles the v4 master diagram ({@code SENT, EDITED, DELETED}) with the
 * Phase A9 delivery requirement ({@code SENT → DELIVERED → READ}):
 * <ul>
 *   <li>{@code SENT} — persisted and broadcast, not yet observed by recipient(s).</li>
 *   <li>{@code DELIVERED} — pushed to recipient session(s) / available in history.</li>
 *   <li>{@code READ} — recipient marked read up to this message (via {@code lastReadAt}).</li>
 *   <li>{@code EDITED} — content edited after send ({@code editedAt} set).</li>
 *   <li>{@code DELETED} — soft-deleted ({@code deletedAt} set, content redacted, row kept for audit).</li>
 * </ul>
 * Stored as {@code VARCHAR} so extending values needs no DB enum migration.
 */
public enum MessageStatus {
    SENT,
    DELIVERED,
    READ,
    EDITED,
    DELETED
}
