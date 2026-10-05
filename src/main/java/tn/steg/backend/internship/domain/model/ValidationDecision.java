package tn.steg.backend.internship.domain.model;

/**
 * The Admin's MANUAL decision on one validation document (AGENTS.md §5.11
 * step 4: "the Admin records his decision per document (validated / rejected +
 * comment)").
 *
 * <p>This is deliberately its own enum and NOT an application status and NOT
 * {@link tn.steg.backend.workflow.domain.model.ApprovalDecision}: a validation
 * decision says "I checked this report/journal myself and I accept or refuse
 * it", which is a different question from whether an internship application was
 * approved. The AI verification result is advisory only (§7.3) and can never
 * produce one of these values — it is persisted as a separate run record.
 *
 * <p>The internship AGGREGATE status ({@link InternshipStatus#UNDER_VALIDATION}
 * → {@link InternshipStatus#VALIDATED}) is derived from these decisions once
 * every required document has a {@link #VALIDATED} one; a {@link #REJECTED}
 * decision keeps the internship in {@code UNDER_VALIDATION} so the Admin can
 * act on it.
 */
public enum ValidationDecision {
    /** The Admin examined the document and validated it. */
    VALIDATED,
    /** The Admin examined the document and refused it; a comment is mandatory. */
    REJECTED
}
