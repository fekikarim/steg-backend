package tn.steg.backend.candidate.application.dto;

/**
 * Account-validation filter of the staff candidate queue (AGENTS.md §5.1
 * "validation status"). The data model has no separate e-mail-verification
 * step: a front-office account exists and is usable when the linked user row is
 * enabled, so "validation status" maps onto that state (recorded as an
 * assumption in docs/back-office-audit.md).
 */
public enum CandidateAccountFilter {
    /** Linked account exists and is enabled. */
    ACTIVE,
    /** No linked account, or the linked account is disabled. */
    DISABLED
}
