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
