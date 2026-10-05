package tn.steg.backend.support;

import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.List;
import java.util.UUID;

/**
 * S6b — test fixture that drives an internship along the §4 chain through the
 * ONE authority ({@link InternshipLifecycleService}).
 *
 * <p>It replaces the deleted legacy internship workflow engine in test setup
 * code. It does NOT skip steps: reaching {@code VALIDATED} walks
 * {@code IN_PROGRESS → REPORT_SUBMITTED → UNDER_VALIDATION → VALIDATED} through
 * the authority, so every intermediate transition is audited and notified
 * exactly as in production. The old engine jumped straight from
 * {@code APPROVED} to {@code VALIDATED} in one call.
 *
 * <p>The step names ("ACTIVE"/"COMPLETED") are kept in the signature only where
 * a caller used them; the vocabulary itself lives in {@link InternshipStatus}.
 */
public final class InternshipLifecycleFixture {

    /** Chain prefix: APPROVED is the creation state, not a transition. */
    private static final List<InternshipStatus> CHAIN = List.of(
            InternshipStatus.IN_PROGRESS,
            InternshipStatus.REPORT_SUBMITTED,
            InternshipStatus.UNDER_VALIDATION,
            InternshipStatus.VALIDATED);

    private InternshipLifecycleFixture() {
    }

    /** Starts the internship (APPROVED → IN_PROGRESS). */
    public static void start(InternshipLifecycleService lifecycle, UUID internshipId, UserPrincipal actor) {
        lifecycle.transition(internshipId, InternshipStatus.IN_PROGRESS, "test fixture: start", actor);
    }

    /**
     * Walks the chain up to (and including) {@code VALIDATED}. Idempotent in
     * the sense that a no-op is not an error: if the internship is already past
     * a step, that step is skipped.
     */
    public static void validate(InternshipLifecycleService lifecycle,
                                InternshipRepository repository,
                                UUID internshipId,
                                UserPrincipal actor) {
        InternshipStatus current = repository.findById(internshipId)
                .map(i -> i.getStatus())
                .orElseThrow(() -> new AssertionError("internship not found: " + internshipId));
        for (InternshipStatus step : CHAIN) {
            if (current.ordinal() >= step.ordinal()) {
                continue;
            }
            lifecycle.transition(internshipId, step, "test fixture: advance to " + step, actor);
            current = step;
        }
    }

    /** Starts then validates — the "finished internship" fixture most tests need. */
    public static void startAndValidate(InternshipLifecycleService lifecycle,
                                        InternshipRepository repository,
                                        UUID internshipId,
                                        UserPrincipal actor) {
        InternshipStatus current = repository.findById(internshipId)
                .map(i -> i.getStatus())
                .orElseThrow(() -> new AssertionError("internship not found: " + internshipId));
        if (current == InternshipStatus.APPROVED) {
            start(lifecycle, internshipId, actor);
        }
        validate(lifecycle, repository, internshipId, actor);
    }
}