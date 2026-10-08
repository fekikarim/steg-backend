package tn.steg.backend.companion.application.dto;

/**
 * T10/SU-VAL-01 (D2 first-level review) — the supervisor long-presses a
 * received document and registers it as the internship's journal or report.
 * The value is validated server-side ({@code JOURNAL} / {@code REPORT},
 * case-insensitive); a missing or unknown value is a coded 422, never a
 * silently ignored field.
 */
public record RegisterDocumentKindRequest(String documentKind) {
}
