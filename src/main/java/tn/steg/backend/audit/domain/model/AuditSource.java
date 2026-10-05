package tn.steg.backend.audit.domain.model;

/**
 * Origin channel of an audited action (AGENTS.md §8.2). Recorded on every
 * row so the Admin viewer can filter by where the action came from.
 */
public enum AuditSource {
    FRONT_OFFICE,
    BACK_OFFICE,
    MOBILE,
    SYSTEM,
    AI
}
