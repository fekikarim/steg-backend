package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the supervisor's own supervised-internship list (T12/B2).
 *
 * <p>Scope is always the caller's supervised internships —
 * {@code SupervisionScopeService.assignedInternships} — including for an
 * ADMIN caller (D1b: the mobile supervisor shell shows own students, never
 * the global list; {@code GET /api/internships} stays the global endpoint
 * for ADMIN). Counts mirror the mobile card semantics so one call replaces
 * the per-intern fan-out: tasks in staff view (hidden scheduled tasks
 * included, exactly as the supervisor's own task reads), done means
 * {@code APPROVED} only (A2), pending journal counts DRAFT + REJECTED +
 * SUBMITTED entries, {@code submittedJournal} counts SUBMITTED only (the
 * review-queue tile), pending deliverables counts SUBMITTED ones.
 */
@Schema(description = "Supervised internship with server-aggregated workload counts")
public record SupervisedInternshipResponse(
        UUID internshipId,
        String reference,
        String internName,
        InternshipStatus status,
        InternshipType type,
        LocalDate startDate,
        LocalDate endDate,
        String departmentName,
        long tasksTotal,
        long tasksCompleted,
        long pendingJournal,
        long submittedJournal,
        long pendingDeliverables,
        long evaluationsCount
) {}
