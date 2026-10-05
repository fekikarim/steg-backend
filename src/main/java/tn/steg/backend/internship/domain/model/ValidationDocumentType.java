package tn.steg.backend.internship.domain.model;

/**
 * The two documents of the §5.11 validation (AGENTS.md §5.11 step 2): the STEG
 * internship report and the internship journal. Both are submitted by the
 * intern through the deliverables channel (assumption #18) and each gets its
 * own AI verification runs and its own manual Admin decision.
 */
public enum ValidationDocumentType {
    REPORT,
    JOURNAL
}
