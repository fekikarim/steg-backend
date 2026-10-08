package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;
import tn.steg.backend.companion.application.dto.DeliverableResponse;
import tn.steg.backend.companion.application.dto.JournalEntryResponse;
import tn.steg.backend.companion.application.dto.TaskResponse;
import tn.steg.backend.evaluation.application.dto.EvaluationResponse;
import tn.steg.backend.notification.application.dto.NotificationResponse;

import java.util.List;

/**
 * One-call student home snapshot (T13/B13).
 *
 * <p>Every section reuses the exact DTO and service call the corresponding
 * list screen uses, with the caller's own authority — so these numbers and
 * rows cannot drift from the lists (same code paths, same visibility
 * rules: hidden scheduled tasks excluded for the intern, done means
 * {@code APPROVED} only). Page sizes mirror the mobile reads (tasks 50,
 * journal 20, deliverables 50, evaluations 10, notifications 5); counts
 * come from {@code totalElements}. Live unread badges stay separate calls
 * (shell-global live state — bundling them would stale the badges).
 */
@Schema(description = "Student home summary: one scoped read for the whole screen")
public record InternshipSummaryResponse(
        InternshipResponse internship,
        List<InternshipAssignmentResponse> assignments,
        Page<TaskResponse> tasks,
        long tasksCompletedTotal,
        long tasksGrandTotal,
        Page<JournalEntryResponse> journal,
        long pendingJournalTotal,
        long journalValidatedTotal,
        Page<DeliverableResponse> deliverables,
        long deliverablesTotal,
        Page<EvaluationResponse> evaluations,
        Page<NotificationResponse> notifications
) {}
