package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.DeliverableStatus;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.InternshipJournalRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;
import tn.steg.backend.internship.application.dto.SupervisedInternshipResponse;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;

import java.util.List;

/**
 * T12/B2 — the caller's own supervised internships with server-aggregated
 * workload counts (one call replaces the mobile per-intern fan-out).
 *
 * <p>Scope is {@code SupervisionScopeService.assignedInternships} for every
 * staff caller, ADMIN included (D1b/BR-03: one definition of "my
 * candidates"; the mobile supervisor shell never sees the global list).
 * Counts reuse the exact semantics of the supervisor's own reads: staff
 * task view (hidden scheduled tasks included), done = {@code APPROVED}
 * only (A2/BR-11), journal DRAFT + REJECTED + SUBMITTED, deliverables
 * SUBMITTED. Read-only aggregation — no migration, no state change.
 */
@Service
@RequiredArgsConstructor
public class SupervisedInternshipService {

    private final SupervisionScopeService supervisionScopeService;
    private final InternshipAssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final InternshipJournalRepository journalRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DeliverableRepository deliverableRepository;
    private final EvaluationDomainRepository evaluationRepository;

    @Transactional(readOnly = true)
    public List<SupervisedInternshipResponse> supervisedInternships(UserPrincipal actor) {
        return supervisionScopeService.assignedInternships(actor).stream()
                .map(this::aggregate)
                .toList();
    }

    private SupervisedInternshipResponse aggregate(Internship internship) {
        String internName = internship.getCandidate() != null
                ? internship.getCandidate().getFirstName() + " " + internship.getCandidate().getLastName()
                : null;
        String department = assignmentRepository
                .findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE)
                .map(a -> a.getDestination() != null ? a.getDestination().getName() : null)
                .orElse(null);

        long tasksTotal = taskRepository.findByInternshipId(internship.getId()).size();
        long tasksCompleted = taskRepository.findByInternshipId(internship.getId()).stream()
                .filter(t -> t.getStatus() == TaskStatus.APPROVED)
                .count();

        long pendingJournal = 0L;
        long submittedJournal = 0L;
        var journal = journalRepository.findByInternshipId(internship.getId());
        if (journal.isPresent()) {
            var entries = journalEntryRepository.findByJournalId(journal.get().getId());
            pendingJournal = entries.stream()
                    .filter(e -> e.getStatus() == JournalEntryStatus.DRAFT
                            || e.getStatus() == JournalEntryStatus.REJECTED
                            || e.getStatus() == JournalEntryStatus.SUBMITTED)
                    .count();
            submittedJournal = entries.stream()
                    .filter(e -> e.getStatus() == JournalEntryStatus.SUBMITTED)
                    .count();
        }

        long pendingDeliverables = deliverableRepository.findByInternshipId(internship.getId()).stream()
                .filter(d -> d.getStatus() == DeliverableStatus.SUBMITTED)
                .count();

        long evaluationsCount = evaluationRepository
                .findByInternshipId(internship.getId(), Pageable.ofSize(1))
                .getTotalElements();

        return new SupervisedInternshipResponse(
                internship.getId(),
                internship.getReference(),
                internName,
                internship.getStatus(),
                internship.getType(),
                internship.getStartDate(),
                internship.getEndDate(),
                department,
                tasksTotal,
                tasksCompleted,
                pendingJournal,
                submittedJournal,
                pendingDeliverables,
                evaluationsCount);
    }
}
