package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.event.InternshipCompletedEvent;
import tn.steg.backend.common.domain.event.InternshipStatusChangedEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.InvalidStateTransitionException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit internship status machine (AGENTS.md §4).
 *
 * <pre>
 *   APPROVED ──→ IN_PROGRESS ──→ REPORT_SUBMITTED ──→ UNDER_VALIDATION
 *        ──→ VALIDATED ──→ RECEIPT_ISSUED
 * </pre>
 *
 * <p>This is the single authority for those transitions: any other pair is
 * rejected with {@link InvalidStateTransitionException} (HTTP 409). The table
 * below is written by hand from AGENTS.md §4 — never derived from clients.
 *
 * <p>Capability rules (AGENTS.md §3.3): validation steps (UNDER_VALIDATION,
 * VALIDATED) and receipt issuance are Admin-only; starting the internship can
 * be done by the Admin or the assigned supervisor; the intern may submit the
 * report. Every accepted transition is audited and announced through
 * {@link InternshipStatusChangedEvent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternshipLifecycleService {

    /**
     * Hand-written §4 transition table: current → allowed next statuses. The
     * {@code UNDER_VALIDATION → REPORT_SUBMITTED} return is the S7
     * resubmission path (audit assumption #18): a REJECTED manual document
     * decision sends the internship back so the intern resubmits, instead of
     * stranding it in validation. Every other pair is 409.
     */
    private static final Map<InternshipStatus, Set<InternshipStatus>> ALLOWED_TRANSITIONS = Map.of(
            InternshipStatus.APPROVED, Set.of(InternshipStatus.IN_PROGRESS),
            InternshipStatus.IN_PROGRESS, Set.of(InternshipStatus.REPORT_SUBMITTED),
            InternshipStatus.REPORT_SUBMITTED, Set.of(InternshipStatus.UNDER_VALIDATION),
            InternshipStatus.UNDER_VALIDATION, Set.of(InternshipStatus.VALIDATED, InternshipStatus.REPORT_SUBMITTED),
            InternshipStatus.VALIDATED, Set.of(InternshipStatus.RECEIPT_ISSUED)
    );

    private final InternshipRepository internshipRepository;
    private final SupervisionScopeService supervisionScopeService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * S6b: statuses that are RESERVED for the validation/receipt use case
     * (AGENTS.md §5.11). They are reachable only through the manual validation
     * decision and the payment-receipt generation, never through the generic
     * endpoint, because validation is a human decision the Admin takes after
     * reading the report and the journal — a bare "set VALIDATED" call would
     * let anybody skip that decision.
     */
    public static final Set<InternshipStatus> RESERVED_FOR_VALIDATION = Set.of(
            InternshipStatus.VALIDATED,
            InternshipStatus.RECEIPT_ISSUED);

    /**
     * Creation only: a brand-new internship starts at {@code APPROVED} (§4 — an
     * internship exists only once the application is approved). This is NOT a
     * transition: no audit entry, no notification, no capability check.
     *
     * <p>It exists so this class is the ONLY writer of
     * {@code Internship.status} (enforced by an ArchUnit rule), including for a
     * freshly built, not-yet-persisted entity.
     */
    public void initializeStatus(Internship internship) {
        internship.setStatus(InternshipStatus.APPROVED);
    }

    /**
     * Administrative cancellation (Admin or the assigned supervisor). Exits the
     * §4 chain from any non-terminal state; a validated internship can no longer
     * be cancelled (§5.11 already produced its receipt).
     */
    @Transactional
    public InternshipResponse cancel(UUID internshipId, UserPrincipal actor) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
        InternshipStatus current = internship.getStatus();
        if (current == InternshipStatus.VALIDATED || current == InternshipStatus.RECEIPT_ISSUED) {
            throw new BusinessRuleException("INTERNSHIP_ALREADY_COMPLETED",
                    "Completed internship cannot be cancelled.");
        }
        boolean isAdmin = actor != null && actor.hasRole("ADMIN");
        boolean isAssigned = actor != null && supervisionScopeService.isAssignedTo(actor, internshipId);
        if (!isAdmin && !isAssigned) {
            throw new BusinessRuleException("INTERNSHIP_TRANSITION_FORBIDDEN",
                    "Only the Admin or the assigned supervisor can cancel this internship.");
        }
        internship.setStatus(InternshipStatus.CANCELLED);
        if (internship.getCancelledAt() == null) {
            internship.setCancelledAt(java.time.Instant.now());
        }
        Internship saved = internshipRepository.save(internship);
        auditService.log("INTERNSHIP_CANCELLED", "Internship", saved.getId(),
                Map.of("status", String.valueOf(current)),
                Map.of("status", InternshipStatus.CANCELLED.name()),
                actor != null ? actor.getId() : null, null);
        return InternshipResponse.from(saved);
    }

    @Transactional
    public InternshipResponse transition(UUID internshipId,
                                         InternshipStatus targetStatus,
                                         String comment,
                                         UserPrincipal actor) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));

        InternshipStatus current = internship.getStatus();
        if (targetStatus == null || !ALLOWED_TRANSITIONS
                .getOrDefault(current, Set.of())
                .contains(targetStatus)) {
            throw new InvalidStateTransitionException(
                    current != null ? current.name() : "NULL",
                    targetStatus != null ? targetStatus.name() : "NULL",
                    "Transition not allowed by the internship status model (AGENTS.md §4). Current: "
                            + current + ", requested: " + targetStatus);
        }

        requireCapability(targetStatus, internship, actor);

        internship.setStatus(targetStatus);
        Internship saved = internshipRepository.save(internship);

        UUID supervisorUserId = supervisionScopeService.findSupervisorUserId(saved.getId()).orElse(null);
        UUID internUserId = saved.getCandidate() != null && saved.getCandidate().getUser() != null
                ? saved.getCandidate().getUser().getId()
                : null;

        auditService.log("INTERNSHIP_STATUS_CHANGED", "Internship", saved.getId(),
                Map.of("status", String.valueOf(current)),
                Map.of("status", targetStatus.name(),
                        "comment", comment != null ? comment : ""),
                actor != null ? actor.getId() : null, null);

        eventPublisher.publishEvent(new InternshipStatusChangedEvent(
                saved.getId(), saved.getReference(),
                String.valueOf(current), targetStatus.name(), comment,
                supervisorUserId, internUserId,
                actor != null ? actor.getId() : null));

        if (targetStatus == InternshipStatus.VALIDATED) {
            // S6b: the legacy internship workflow engine used to publish this
            // event when its COMPLETED step fired. The engine is gone, so the
            // business fact now comes from the single authority (evaluation
            // module listens: a FINAL report may still be missing).
            eventPublisher.publishEvent(new InternshipCompletedEvent(
                    saved.getId(),
                    saved.getReference(),
                    candidateFullName(saved),
                    actor != null ? actor.getId() : null));
        }

        log.info("Internship lifecycle transition: ref={} {} → {} by actor={}",
                saved.getReference(), current, targetStatus, actor != null ? actor.getId() : null);
        return InternshipResponse.from(saved);
    }

    private void requireCapability(InternshipStatus targetStatus, Internship internship, UserPrincipal actor) {
        boolean isAdmin = actor != null && actor.hasRole("ADMIN");
        boolean isAssigned = actor != null && supervisionScopeService.isAssignedTo(actor, internship.getId());
        boolean isIntern = actor != null
                && internship.getCandidate() != null
                && internship.getCandidate().getUser() != null
                && actor.getId().equals(internship.getCandidate().getUser().getId());

        switch (targetStatus) {
            case IN_PROGRESS -> {
                if (!isAdmin && !isAssigned) {
                    throw new BusinessRuleException("INTERNSHIP_TRANSITION_FORBIDDEN",
                            "Only the Admin or the assigned supervisor can start this internship.");
                }
            }
            case REPORT_SUBMITTED -> {
                if (!isAdmin && !isAssigned && !isIntern) {
                    throw new BusinessRuleException("INTERNSHIP_TRANSITION_FORBIDDEN",
                            "Only the intern, the Admin or the assigned supervisor can submit the report.");
                }
            }
            // Validation and receipt issuance are Admin-only (AGENTS.md §3.3).
            case UNDER_VALIDATION, VALIDATED, RECEIPT_ISSUED -> {
                if (!isAdmin) {
                    throw new BusinessRuleException("INTERNSHIP_TRANSITION_FORBIDDEN",
                            "Only the Admin can run the validation steps of an internship.");
                }
            }
            default -> throw new InvalidStateTransitionException(
                    internship.getStatus() != null ? internship.getStatus().name() : "NULL",
                    targetStatus.name(), "Unsupported internship transition target: " + targetStatus);
        }
    }

    /** Intern's display name — never the national id. */
    private static String candidateFullName(Internship internship) {
        if (internship.getCandidate() == null) {
            return "";
        }
        return (internship.getCandidate().getFirstName() + " " + internship.getCandidate().getLastName()).strip();
    }
}
