package tn.steg.backend.companion.domain.model;

import java.util.Collection;

/**
 * ONE shared definition of "task is done" for the journal 75% rule
 * (AGENTS.md §13 A2): done = completed <b>and</b> approved by a
 * supervisor/admin, i.e. status {@code APPROVED} (approval implies prior
 * completion — the review endpoint only accepts COMPLETED tasks). The
 * denominator counts the internship's tasks excluding CANCELLED ones
 * (cancelled = permanently out, the "denied-permanently" case); hard-deleted
 * rows are absent by construction. Used by the S7 validation input and exposed
 * to the UI through the validation detail — never re-implemented per caller.
 *
 * <p>T04/D8: not-yet-visible scheduled tasks are excluded from the
 * denominator as well (D8b) through {@link #withoutHidden} — the single
 * place that knows what "hidden" means, reused by validation, the
 * classification board and the scheduler sweep.
 */
public final class TaskCompletionPolicy {

    private TaskCompletionPolicy() {
    }

    public static boolean isDone(Task task) {
        return task != null && task.getStatus() == TaskStatus.APPROVED;
    }

    public static boolean countsTowardTotal(Task task) {
        return task != null && task.getStatus() != TaskStatus.CANCELLED;
    }

    /**
     * T04/D8: a task scheduled for the future is hidden from the student
     * until {@code now} reaches {@code visibleFrom} (null = immediate).
     * Absolute comparison only — no device clock, no time zone guesswork.
     */
    public static boolean isHidden(Task task, java.time.Instant now) {
        return task != null && task.getVisibleFrom() != null
                && now != null && task.getVisibleFrom().isAfter(now);
    }

    /** The tasks a student is allowed to see right now (staff sees all). */
    public static java.util.List<Task> withoutHidden(
            Collection<Task> tasks, java.time.Instant now) {
        java.util.List<Task> visible = new java.util.ArrayList<>();
        if (tasks == null) {
            return visible;
        }
        for (Task task : tasks) {
            if (!isHidden(task, now)) {
                visible.add(task);
            }
        }
        return visible;
    }

    public record CompletionRatio(int done, int total) {
    }

    public static CompletionRatio ratio(Collection<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new CompletionRatio(0, 0);
        }
        int total = 0;
        int done = 0;
        for (Task task : tasks) {
            if (!countsTowardTotal(task)) {
                continue;
            }
            total++;
            if (isDone(task)) {
                done++;
            }
        }
        return new CompletionRatio(done, total);
    }
}
