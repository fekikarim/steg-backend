package tn.steg.backend.notification.domain.model;

/**
 * Stable notification catalogue key (D11 / BR-44).
 *
 * <p>The catalogue mirrors the facts {@code NotificationEventListener} reacts
 * to plus the T01 additions (task edit/delete, welcome). It is the wire value
 * of {@code notifications.type}, {@code NotificationResponse.type} and
 * {@code NotificationPayload.type}; the mobile app renders it through its own
 * localized mirror and MUST tolerate unknown/legacy (null) values generically.
 *
 * <p>Values are additive and never renamed: a stored key must keep meaning.
 */
public enum NotificationType {
    TASK_ASSIGNED,
    TASK_UPDATED,
    TASK_DELETED,
    TASK_STATUS_CHANGED,
    /** T04/D8: a scheduled task became visible to the student. */
    SCHEDULED_TASK_VISIBLE,
    DOCUMENT_REJECTED,
    DOCUMENT_VERIFIED,
    APPLICATION_SUBMITTED,
    APPLICATION_RESUBMITTED,
    APPLICATION_ACCEPTED,
    APPLICATION_REJECTED,
    APPLICATION_MODIFICATION_REQUESTED,
    CANDIDATE_VALIDATED,
    INTERNSHIP_ASSIGNED,
    INTERNSHIP_STATUS_CHANGED,
    INTERNSHIP_REPORT_SUBMITTED,
    JOURNAL_ENTRY_VALIDATED,
    FINAL_EVALUATION_REQUIRED,
    PAYMENT_APPROVED,
    CERTIFICATE_AVAILABLE,
    MESSAGE_RECEIVED,
    WELCOME,
    /** T08/D7: someone commented on your community post. */
    COMMUNITY_COMMENT,
    /** T08/D7: a moderator removed your community post (reason in message). */
    COMMUNITY_POST_REMOVED,
    /** T08/D7: a moderator removed your community comment (reason in message). */
    COMMUNITY_COMMENT_REMOVED;

    /**
     * Tolerant parse for the wire: unknown or absent values become
     * {@code null} so a newer backend value can never crash an older
     * consumer (and legacy rows render generically).
     */
    public static NotificationType fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
