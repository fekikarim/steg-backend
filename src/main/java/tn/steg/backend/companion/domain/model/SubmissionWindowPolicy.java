package tn.steg.backend.companion.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * T10/B7 — ONE definition of the internship-document SUBMISSION window
 * (AGENTS.md D3, BR-22, ST-VAL-01): in the last week of ANY internship type
 * the student must send both the journal and the report, and outside that
 * window the server refuses the submission.
 *
 * <p>Rules, exactly as the owner-confirmed defaults state them:
 * <ul>
 *   <li>the window is the final <b>7 days</b> of the internship, for every
 *       internship type — {@code [end − 7 days, end]} inclusive, so the end
 *       date itself is always inside;</li>
 *   <li><b>late is refused</b>: unlike the journal-generation window
 *       ({@link JournalEligibilityPolicy}, D3b), a submission after
 *       {@code end} answers {@code AFTER_WINDOW} — the documented T10 edge
 *       case ("the app explains and offers to contact the supervisor, not an
 *       override");</li>
 *   <li>a cancelled internship or one without a usable period never opens the
 *       window.</li>
 * </ul>
 *
 * <p>Pure by design: the caller supplies "today" resolved through
 * {@code ApplicationTimeZone} ({@code Africa/Tunis}), so neither the device
 * clock nor the JVM default zone can change the answer (BR-21/BR-53).
 */
public final class SubmissionWindowPolicy {

    /** BR-22: the submission window is the final week of any internship type. */
    public static final int WINDOW_DAYS = 7;

    private SubmissionWindowPolicy() {
    }

    /** Why the window is (not) open — a coded, translatable reason. */
    public enum Reason {
        /** Today is inside {@code [opensAt, end]}. */
        OPEN,
        /** The final week has not started yet (BR-22). */
        BEFORE_WINDOW,
        /** The internship ended: submissions are refused, not late-accepted. */
        AFTER_WINDOW,
        /** The internship has no usable start/end period. */
        NO_PERIOD,
        /** A cancelled internship accepts no validation documents. */
        CANCELLED
    }

    /**
     * @param open          server-authoritative decision
     * @param opensAt       first eligible day ({@code end − 7}, null when unknown)
     * @param closesAt      the internship end date (null when unknown)
     * @param reason        coded reason
     * @param daysUntilOpen calendar days until {@code opensAt} (0 when open or unknown)
     * @param windowDays    the window length (0 when unknown)
     */
    public record Decision(
            boolean open,
            LocalDate opensAt,
            LocalDate closesAt,
            Reason reason,
            int daysUntilOpen,
            int windowDays) {
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
        LocalDate opensAt = end.minusDays(WINDOW_DAYS);
        if (today.isBefore(opensAt)) {
            int daysUntilOpen = (int) ChronoUnit.DAYS.between(today, opensAt);
            return new Decision(false, opensAt, end, Reason.BEFORE_WINDOW, daysUntilOpen, WINDOW_DAYS);
        }
        if (today.isAfter(end)) {
            return new Decision(false, opensAt, end, Reason.AFTER_WINDOW, 0, WINDOW_DAYS);
        }
        return new Decision(true, opensAt, end, Reason.OPEN, 0, WINDOW_DAYS);
    }
}
