package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.CompanionService;
import tn.steg.backend.companion.application.dto.DeliverableResponse;
import tn.steg.backend.companion.application.dto.JournalEntryResponse;
import tn.steg.backend.companion.application.dto.TaskResponse;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.evaluation.application.EvaluationService;
import tn.steg.backend.evaluation.application.dto.EvaluationResponse;
import tn.steg.backend.internship.application.dto.InternshipAssignmentResponse;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.application.dto.InternshipSummaryResponse;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.application.dto.NotificationResponse;

import java.util.List;
import java.util.UUID;

/**
 * T13/B13 — one-call student home snapshot.
 *
 * <p>Each section delegates to the exact application-service call the
 * corresponding list screen uses, with the caller's own authority — the
 * intern visibility rules (hidden scheduled tasks excluded, done means
 * {@code APPROVED}) and the journal/deliverable scopes apply here exactly
 * as in the lists, so home numbers cannot drift. Read-only composition:
 * no new business logic, no state change.
 */
@Service
@RequiredArgsConstructor
public class InternshipSummaryService {

    private static final Pageable TASKS_PAGE = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
    private static final Pageable JOURNAL_PAGE = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "entryDate"));
    private static final Pageable COUNT_PAGE = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"));
    private static final Pageable JOURNAL_COUNT_PAGE = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "entryDate"));
    private static final Pageable DELIVERABLES_PAGE = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
    private static final Pageable EVALUATIONS_PAGE = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "evaluationDate"));
    private static final Pageable NOTIFICATIONS_PAGE = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"));

    private final InternshipService internshipService;
    private final CompanionService companionService;
    private final EvaluationService evaluationService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public InternshipSummaryResponse summary(UUID internshipId, UserPrincipal actor) {
        InternshipResponse internship = internshipService.getInternship(internshipId, actor);
        List<InternshipAssignmentResponse> assignments = internshipService.listAssignments(internshipId);

        Page<TaskResponse> tasks = companionService.listTasks(internshipId, null, TASKS_PAGE, actor);
        long tasksCompletedTotal = companionService
                .listTasks(internshipId, TaskStatus.APPROVED, COUNT_PAGE, actor).getTotalElements();

        Page<JournalEntryResponse> journal =
                companionService.listJournalEntries(internshipId, null, null, null, JOURNAL_PAGE);
        long drafts = companionService
                .listJournalEntries(internshipId, JournalEntryStatus.DRAFT, null, null, JOURNAL_COUNT_PAGE)
                .getTotalElements();
        long rejected = companionService
                .listJournalEntries(internshipId, JournalEntryStatus.REJECTED, null, null, JOURNAL_COUNT_PAGE)
                .getTotalElements();
        long validated = companionService
                .listJournalEntries(internshipId, JournalEntryStatus.VALIDATED, null, null, JOURNAL_COUNT_PAGE)
                .getTotalElements();

        Page<DeliverableResponse> deliverables = companionService.listDeliverables(internshipId, DELIVERABLES_PAGE);
        Page<EvaluationResponse> evaluations =
                evaluationService.listEvaluations(internshipId, null, EVALUATIONS_PAGE);
        Page<NotificationResponse> notifications =
                notificationService.listMine(actor, false, NOTIFICATIONS_PAGE);

        return new InternshipSummaryResponse(
                internship,
                assignments,
                tasks,
                tasksCompletedTotal,
                tasks.getTotalElements(),
                journal,
                drafts + rejected,
                validated,
                deliverables,
                deliverables.getTotalElements(),
                evaluations,
                notifications);
    }
}
