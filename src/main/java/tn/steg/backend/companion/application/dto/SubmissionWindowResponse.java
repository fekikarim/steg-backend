package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.SubmissionWindowPolicy;

import java.time.LocalDate;

/**
 * T10/B7 — the server-computed final-week submission window (BR-22) so the
 * app can show/hide the journal/report submission action honestly instead of
 * guessing from the device clock (BR-21/BR-53). A refusal on the submit path
 * carries the coded {@code SUBMISSION_NOT_IN_WINDOW}; this payload explains
 * it with dates and a stable reason.
 *
 * @param open          whether a validation document may be submitted today
 * @param opensAt       first day of the final week ({@code end − 7})
 * @param closesAt      the internship end date — the last accepted day
 * @param reason        {@code OPEN | BEFORE_WINDOW | AFTER_WINDOW | NO_PERIOD | CANCELLED}
 * @param daysUntilOpen calendar days until {@code opensAt} (0 when open/unknown)
 * @param windowDays    window length in days (7, or 0 when the period is unusable)
 */
public record SubmissionWindowResponse(
        boolean open,
        LocalDate opensAt,
        LocalDate closesAt,
        String reason,
        int daysUntilOpen,
        int windowDays) {

    public static SubmissionWindowResponse from(SubmissionWindowPolicy.Decision decision) {
        return new SubmissionWindowResponse(
                decision.open(),
                decision.opensAt(),
                decision.closesAt(),
                decision.reason().name(),
                decision.daysUntilOpen(),
                decision.windowDays());
    }
}
