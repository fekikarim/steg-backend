package tn.steg.backend.community.domain.model;

/**
 * Lifecycle of a community report (T08 / D7): {@code OPEN} until a
 * moderator resolves it. Resolution is terminal and audited; dismissal is
 * recorded as a resolution (e.g. "no violation found").
 */
public enum CommunityReportStatus {
    OPEN,
    RESOLVED
}
