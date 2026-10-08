package tn.steg.backend.companion.application.dto;

/**
 * T09/B6 result: the journal deliverable the server just rendered plus the
 * task-completion facts the student was warned about before generating
 * (BR-24/A2 — the warning is non-blocking, the back-office is the referee).
 *
 * @param deliverable       the stored draft deliverable (identity of the
 *                          journal document: BR-33 interim rule, see the
 *                          service javadoc)
 * @param source            {@code TASKS} or {@code TEXT}
 * @param taskCount         A2 denominator at generation time
 * @param approvedTasks     A2 numerator at generation time
 * @param belowThreshold    fewer than 75 % approved-done (warning only)
 * @param replacedDraft     {@code true} when the previous unsubmitted journal
 *                          draft was replaced by a new version of the same
 *                          deliverable (ST-JRN-06 regenerate)
 * @param previousSubmitted {@code true} when an earlier journal deliverable was
 *                          already submitted/validated and stayed immutable,
 *                          so this generation is a NEW draft — the student is
 *                          told (T09 edge case)
 */
public record JournalGenerationResponse(
        DeliverableResponse deliverable,
        String source,
        int taskCount,
        int approvedTasks,
        boolean belowThreshold,
        boolean replacedDraft,
        boolean previousSubmitted) {

    public static final String SOURCE_TASKS = "TASKS";
    public static final String SOURCE_TEXT = "TEXT";
}
