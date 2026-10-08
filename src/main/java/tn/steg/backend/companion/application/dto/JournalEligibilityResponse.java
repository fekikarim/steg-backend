package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.JournalEligibilityPolicy;

import java.time.LocalDate;

/**
 * T09/B5 — server-authoritative journal eligibility (BR-20/BR-21/BR-61) with
 * the shared task-completion numbers of the same read (BR-24/A2), so the
 * mobile action never has to compute a window or a ratio from the device
 * clock.
 *
 * @param eligible      the server's decision; the generation endpoint enforces
 *                      the same rule
 * @param opensAt       first eligible day (ISO {@code yyyy-MM-dd})
 * @param closesAt      the internship end date — informational: after it the
 *                      window stays open with {@code ELIGIBLE_LATE}
 * @param reason        coded reason ({@link JournalEligibilityPolicy.Reason})
 * @param daysUntilOpen server-computed calendar days until {@code opensAt}
 * @param windowDays    the D3 branch length (14 or 30)
 * @param taskCount     tasks counted by A2 (visible, not cancelled) — the very
 *                      same denominator the back-office verification uses
 * @param approvedTasks tasks with status {@code APPROVED}
 * @param belowThreshold {@code true} when fewer than 75 % of the counted tasks
 *                      are approved-done (warning only, never a block)
 */
public record JournalEligibilityResponse(
        boolean eligible,
        String opensAt,
        String closesAt,
        String reason,
        int daysUntilOpen,
        int windowDays,
        int taskCount,
        int approvedTasks,
        boolean belowThreshold) {

    public static JournalEligibilityResponse from(
            JournalEligibilityPolicy.Decision decision, int taskCount, int approvedTasks,
            boolean belowThreshold) {
        return new JournalEligibilityResponse(
                decision.eligible(),
                iso(decision.opensAt()),
                iso(decision.closesAt()),
                decision.reason() != null ? decision.reason().name() : null,
                decision.daysUntilOpen(),
                decision.windowDays(),
                taskCount,
                approvedTasks,
                belowThreshold);
    }

    private static String iso(LocalDate date) {
        return date != null ? date.toString() : null;
    }
}
