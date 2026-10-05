package tn.steg.backend.internship.domain.model;

/**
 * Explicit internship lifecycle (AGENTS.md §4):
 * {@code APPROVED → IN_PROGRESS → REPORT_SUBMITTED → UNDER_VALIDATION →
 * VALIDATED → RECEIPT_ISSUED}, plus the terminal {@code CANCELLED} and
 * {@code ARCHIVED} of §13.
 *
 * <p>The legacy values {@code PLANNED}, {@code ACTIVE} and {@code COMPLETED}
 * were REMOVED in S1b. They duplicated {@link #APPROVED},
 * {@link #IN_PROGRESS} and {@link #VALIDATED} respectively; existing rows were
 * migrated by {@code V43} and the column default is now {@code APPROVED}
 * (audit assumption #15). The manual per-document decision of the validation
 * dialog is NOT a status: it lives in its own enum,
 * {@link tn.steg.backend.internship.domain.model.ValidationDecision}.
 */
public enum InternshipStatus {
    APPROVED,
    IN_PROGRESS,
    REPORT_SUBMITTED,
    UNDER_VALIDATION,
    VALIDATED,
    RECEIPT_ISSUED,
    CANCELLED,
    ARCHIVED
}
