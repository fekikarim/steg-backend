package tn.steg.backend.companion.domain.model;

/**
 * Lifecycle of an official internship logbook.
 * DRAFT → SUBMITTED → VALIDATED/REJECTED → OFFICIAL
 */
public enum LogbookStatus {
    DRAFT,
    SUBMITTED,
    VALIDATED,
    REJECTED,
    OFFICIAL
}