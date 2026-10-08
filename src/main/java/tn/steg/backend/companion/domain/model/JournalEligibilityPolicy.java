package tn.steg.backend.companion.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * T09/B5 — ONE definition of the internship-journal eligibility window
 * (AGENTS.md D3/D3b, BR-20/BR-21/BR-61).
 *
 * <p>Rules, exactly as the owner-confirmed defaults state them:
 * <ul>
 *   <li>an internship shorter than <b>3 calendar months</b> ({@code end <
 *       start.plusMonths(3)}) opens the window for the final <b>14 days</b>;
 *       otherwise — including <b>exactly</b> 3 months — for the final
 *       <b>30 days</b>;</li>
 *   <li>the window is {@code [end − N days, end]} inclusive, so the internship
 *       end date itself is always eligible;</li>
 *   <li>after {@code end} the window stays open (a late journal is allowed) and
 *       the reason says so.</li>
 * </ul>
 *
 * <p>The rule is pure: the caller supplies "today" resolved by
 * {@code ApplicationTimeZone} ({@code Africa/Tunis}), so neither the device
 * clock nor the JVM default zone can change the answer (BR-21).
 */
public final class JournalEligibilityPolicy {

    /** D3: window length for an internship shorter than 3 calendar months. */
    public static final int SHORT_INTERNSHIP_WINDOW_DAYS = 14;

    /** D3: window length otherwise (3 calendar months or longer). */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** D3: the calendar-month length that switches branches (inclusive). */
    public static final int SHORT_INTERNSHIP_MONTHS = 3;

    private JournalEligibilityPolicy() {
    }

    /** Why the window is (not) open — a coded, translatable reason. */
    public enum Reason {
        /** Today is inside {@code [opensAt, end]}. */
        ELIGIBLE_WINDOW,
        /** The internship already ended; the window stays open (D3b). */
        ELIGIBLE_LATE,
        /** The window has not opened yet (BR-20). */
        BEFORE_WINDOW,
        /** The internship has no usable start/end period. */
        NO_PERIOD,
        /** A cancelled internship never produces a journal. */
        CANCELLED
    }

    /**
     * @param eligible      server-authoritative decision
     * @param opensAt       first eligible day (null when unknown)
     * @param closesAt      the internship end date — informational, the window
     *                      stays open after it (D3b)
     * @param reason        coded reason
     * @param daysUntilOpen calendar days until {@code opensAt} (0 when eligible,
     *                      or when the period is unusable)
     * @param windowDays    the branch's window length (0 when unknown)
     */
    public record Decision(
            boolean eligible,
            LocalDate opensAt,
            LocalDate closesAt,
            Reason reason,
            int daysUntilOpen,
            int windowDays) {
    }

    /**
     * D3 branch: {@code < 3} calendar months → 14 days, otherwise 30. Computed
     * on calendar months, so 2026-01-05 → 2026-04-05 is exactly 3 months (30
     * days) while 2026-01-05 → 2026-04-04 is shorter (14 days).
     */
    public static int windowDaysFor(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return DEFAULT_WINDOW_DAYS;
        }
        return end.isBefore(start.plusMonths(SHORT_INTERNSHIP_MONTHS))
                ? SHORT_INTERNSHIP_WINDOW_DAYS
                : DEFAULT_WINDOW_DAYS;
    }

    /**
     * @param today     "today" in the application time zone (never the phone's)
     * @param start     internship start date (may be null → NO_PERIOD)
     * @param end       internship end date (may be null → NO_PERIOD)
     * @param cancelled whether the internship is cancelled
     */
    public static Decision evaluate(LocalDate today, LocalDate start, LocalDate end, boolean cancelled) {
        if (cancelled) {
            return new Decision(false, null, end, Reason.CANCELLED, 0, 0);
        }
        if (today == null || start == null || end == null || end.isBefore(start)) {
            return new Decision(false, null, null, Reason.NO_PERIOD, 0, 0);
        }
        int windowDays = windowDaysFor(start, end);
        LocalDate opensAt = end.minusDays(windowDays);
        if (today.isBefore(opensAt)) {
            int daysUntilOpen = (int) ChronoUnit.DAYS.between(today, opensAt);
            return new Decision(false, opensAt, end, Reason.BEFORE_WINDOW, daysUntilOpen, windowDays);
        }
        if (today.isAfter(end)) {
            return new Decision(true, opensAt, end, Reason.ELIGIBLE_LATE, 0, windowDays);
        }
        return new Decision(true, opensAt, end, Reason.ELIGIBLE_WINDOW, 0, windowDays);
    }
}
