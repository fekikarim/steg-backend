package tn.steg.backend.workflow.domain.model;

/**
 * The decision a staff member records on a workflow approval step.
 *
 * <p>S1b: the legacy value {@code NEEDS_CORRECTION} was REMOVED and renamed to
 * {@link #MODIFICATION_REQUESTED} — it is the decision behind §4's
 * {@code MODIFICATION_REQUESTED} status and the old name was a second name for
 * the same thing (audit assumption #15).
 *
 * <p>NOTE for the audit viewer: historical audit rows keep the append-only
 * action code {@code APPLICATION_NEEDS_CORRECTION}. New rows are written with
 * {@code APPLICATION_MODIFICATION_REQUESTED}. Both must stay displayable — an
 * audit screen must never depend on a removed enum value.
 */
public enum ApprovalDecision {
    PENDING,
    APPROVED,
    REJECTED,
    RETURNED,
    MODIFICATION_REQUESTED
}

