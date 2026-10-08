package tn.steg.backend.companion.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09/B5 — the pure D3/D3b window rule, with fixed dates (no clock, no Spring):
 * the exactly-3-months boundary, the calendar-month arithmetic, the inclusive
 * {@code [end − N, end]} window and the open-after-the-end (late) branch.
 */
@DisplayName("T09/B5 — JournalEligibilityPolicy (D3/D3b)")
class JournalEligibilityPolicyTest {

    @ParameterizedTest(name = "{0} → {1} is a {2}-day window")
    @CsvSource({
            // end before start + 3 months → 14 days
            "2026-01-05, 2026-04-03, 14",
            "2026-01-05, 2026-04-04, 14",
            // exactly 3 calendar months and beyond → 30 days
            "2026-01-05, 2026-04-05, 30",
            "2026-01-05, 2026-04-06, 30",
            // month-length clamp: plusMonths(3) lands on 2026-04-30
            "2026-01-31, 2026-04-29, 14",
            "2026-01-31, 2026-04-30, 30",
            // one-day internships and long ones
            "2026-03-01, 2026-03-01, 14",
            "2025-09-01, 2026-03-01, 30",
    })
    @DisplayName("D3: shorter than 3 calendar months → 14 days, otherwise (including exactly 3) → 30")
    void windowLengthFollowsTheCalendarMonthRule(String start, String end, int expectedDays) {
        assertThat(JournalEligibilityPolicy.windowDaysFor(
                LocalDate.parse(start), LocalDate.parse(end))).isEqualTo(expectedDays);
    }

    @Test
    @DisplayName("D3b: the window is [end − N, end] inclusive — open on both boundaries, closed before them")
    void windowIsInclusiveOnBothBoundaries() {
        LocalDate start = LocalDate.parse("2026-01-05");
        LocalDate end = LocalDate.parse("2026-04-04"); // 14-day branch
        LocalDate opensAt = end.minusDays(14);

        JournalEligibilityPolicy.Decision before =
                JournalEligibilityPolicy.evaluate(opensAt.minusDays(1), start, end, false);
        assertThat(before.eligible()).isFalse();
        assertThat(before.reason()).isEqualTo(JournalEligibilityPolicy.Reason.BEFORE_WINDOW);
        assertThat(before.daysUntilOpen()).isEqualTo(1);
        assertThat(before.opensAt()).isEqualTo(opensAt);

        JournalEligibilityPolicy.Decision first =
                JournalEligibilityPolicy.evaluate(opensAt, start, end, false);
        assertThat(first.eligible()).isTrue();
        assertThat(first.reason()).isEqualTo(JournalEligibilityPolicy.Reason.ELIGIBLE_WINDOW);

        JournalEligibilityPolicy.Decision middle =
                JournalEligibilityPolicy.evaluate(end.minusDays(7), start, end, false);
        assertThat(middle.eligible()).isTrue();

        JournalEligibilityPolicy.Decision finalDay =
                JournalEligibilityPolicy.evaluate(end, start, end, false);
        assertThat(finalDay.eligible()).isTrue();
        assertThat(finalDay.reason()).isEqualTo(JournalEligibilityPolicy.Reason.ELIGIBLE_WINDOW);
        assertThat(finalDay.closesAt()).isEqualTo(end);
    }

    @Test
    @DisplayName("D3b: after the end the window stays open and the reason says late")
    void lateJournalStaysOpen() {
        LocalDate start = LocalDate.parse("2026-01-05");
        LocalDate end = LocalDate.parse("2026-04-04");

        JournalEligibilityPolicy.Decision late =
                JournalEligibilityPolicy.evaluate(end.plusDays(1), start, end, false);
        assertThat(late.eligible()).isTrue();
        assertThat(late.reason()).isEqualTo(JournalEligibilityPolicy.Reason.ELIGIBLE_LATE);
        assertThat(late.daysUntilOpen()).isZero();

        JournalEligibilityPolicy.Decision longLate =
                JournalEligibilityPolicy.evaluate(end.plusYears(1), start, end, false);
        assertThat(longLate.eligible()).isTrue();
        assertThat(longLate.reason()).isEqualTo(JournalEligibilityPolicy.Reason.ELIGIBLE_LATE);
    }

    @Test
    @DisplayName("unusable periods and cancelled internships are never eligible")
    void unusablePeriodsAreNotEligible() {
        LocalDate today = LocalDate.parse("2026-03-01");

        JournalEligibilityPolicy.Decision noStart =
                JournalEligibilityPolicy.evaluate(today, null, LocalDate.parse("2026-04-01"), false);
        assertThat(noStart.eligible()).isFalse();
        assertThat(noStart.reason()).isEqualTo(JournalEligibilityPolicy.Reason.NO_PERIOD);

        JournalEligibilityPolicy.Decision noEnd =
                JournalEligibilityPolicy.evaluate(today, LocalDate.parse("2026-01-01"), null, false);
        assertThat(noEnd.eligible()).isFalse();
        assertThat(noEnd.reason()).isEqualTo(JournalEligibilityPolicy.Reason.NO_PERIOD);

        JournalEligibilityPolicy.Decision inverted = JournalEligibilityPolicy.evaluate(
                today, LocalDate.parse("2026-04-01"), LocalDate.parse("2026-01-01"), false);
        assertThat(inverted.eligible()).isFalse();
        assertThat(inverted.reason()).isEqualTo(JournalEligibilityPolicy.Reason.NO_PERIOD);
        assertThat(inverted.windowDays()).isZero();

        JournalEligibilityPolicy.Decision cancelled = JournalEligibilityPolicy.evaluate(
                today, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-01"), true);
        assertThat(cancelled.eligible()).isFalse();
        assertThat(cancelled.reason()).isEqualTo(JournalEligibilityPolicy.Reason.CANCELLED);
        assertThat(cancelled.opensAt()).isNull();
    }

    @Test
    @DisplayName("a midnight-shifted comparison changes the answer (the caller must pass the Tunis day)")
    void theDayIsSuppliedByTheCaller() {
        LocalDate start = LocalDate.parse("2026-01-05");
        LocalDate end = LocalDate.parse("2026-04-04"); // opensAt = 2026-03-21
        assertThat(JournalEligibilityPolicy.evaluate(LocalDate.parse("2026-03-20"), start, end, false)
                .eligible()).isFalse();
        assertThat(JournalEligibilityPolicy.evaluate(LocalDate.parse("2026-03-21"), start, end, false)
                .eligible()).isTrue();
    }
}
