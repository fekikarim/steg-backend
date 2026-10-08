package tn.steg.backend.companion.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.event.InternshipReportSubmittedEvent;
import tn.steg.backend.common.domain.event.JournalEntryValidatedEvent;
import tn.steg.backend.common.domain.event.SupervisorDocumentRejectedEvent;
import tn.steg.backend.common.domain.event.TaskAssignedEvent;
import tn.steg.backend.common.domain.event.TaskDeletedEvent;
import tn.steg.backend.common.domain.event.TaskStatusChangedEvent;
import tn.steg.backend.common.domain.event.TaskUpdatedEvent;
import tn.steg.backend.common.application.idempotency.IdempotencyService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.domain.util.NullSafe;
import tn.steg.backend.companion.application.dto.*;
import tn.steg.backend.companion.domain.model.*;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.DeliverableVersionRepository;
import tn.steg.backend.companion.domain.repository.InternshipJournalRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.common.application.ApplicationTimeZone;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.service.DocumentValidationService;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.MalwareScanner;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionService {

    private final TaskRepository taskRepository;
    private final InternshipJournalRepository journalRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DeliverableRepository deliverableRepository;
    private final DeliverableVersionRepository deliverableVersionRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final FileStorageService fileStorageService;
    private final FileAssetRepository fileAssetRepository;
    private final DocumentValidationService documentValidationService;
    private final MalwareScanner malwareScanner;
    private final AuditService auditService;
    private final SupervisionScopeService supervisionScopeService;
    private final ApplicationTimeZone applicationTimeZone;
    private final InternshipLifecycleService internshipLifecycleService;
    private final IdempotencyService idempotencyService;

    /** Business facts for cross-cutting concerns; never a dependency on consumers (Phase A10). */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * S9 audit channel (§8.2) for intern-initiated writes: these endpoints
     * admit ADMIN or the owning intern only, so a non-admin actor IS the
     * mobile intern (MOBILE), anything else is BACK_OFFICE. Supervisor/admin
     * validations and task writes keep the default.
     */
    private void audit(String action, String entityType, UUID id,
                       Object oldValues, Object newValues, UserPrincipal actor) {
        AuditSource source = actor != null && actor.hasRole("ADMIN")
                ? AuditSource.BACK_OFFICE : AuditSource.MOBILE;
        auditService.log(action, entityType, id, oldValues, newValues,
                actor != null ? actor.getId() : null, null, null, null, source);
    }

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

    @Transactional
    public TaskResponse createTask(UUID internshipId, TaskRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        if (actor != null && !supervisionScopeService.canManage(actor, internshipId) && !isInternOfInternship(internship, actor)) {
            throw new ResourceNotFoundException("Internship not found: " + internshipId);
        }
        User creator = findUserOrThrow(actor.getId());

        User assignedTo = null;
        if (request.assignedToId() != null) {
            assignedTo = findUserOrThrow(request.assignedToId());
        }

        Task task = new Task(internship, creator, request.title(), request.description());
        task.setAssignedTo(assignedTo);
        task.setDueDate(request.dueDate());
        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.COMPLETED) {
                task.setCompletedAt(Instant.now());
            }
        }
        // T04/D8: scheduling is a staff-only write, validated against the
        // internship period. Null on create = visible immediately.
        if (request.visibleFrom() != null) {
            ensureStaffScheduling(actor, internshipId);
            checkVisibleFromWithinPeriod(request.visibleFrom(), internship);
            task.setVisibleFrom(request.visibleFrom());
        }

        task = taskRepository.saveTask(task);
        log.info("Task created: id={}, internship={}, creator={}", task.getId(), internshipId, creator.getId());
        // E2: Map.of forbids nulls — unassigned tasks carry a null assignedToId,
        // so the audit detail uses the null-safe shared builder.
        Map<String, Object> taskAudit = NullSafe.mapOf(
                "internshipId", internshipId,
                "title", task.getTitle(),
                "assignedToId", assignedTo != null ? assignedTo.getId() : null,
                "visibleFrom", task.getVisibleFrom());
        auditService.log("COMPANION_TASK_CREATED", "Task", task.getId(), null,
                taskAudit, actor.getId(), null);

        // Phase A10: notify the assignee (not for self-assigned tasks).
        // T06 §6: a hidden scheduled task notifies nobody yet — the
        // visibility sweep notifies the intern on appearance instead.
        if (assignedTo != null && !assignedTo.getId().equals(creator.getId())
                && !TaskCompletionPolicy.isHidden(task, Instant.now())) {
            eventPublisher.publishEvent(new TaskAssignedEvent(
                    task.getId(), task.getTitle(), internshipId, assignedTo.getId(), actor.getId()));
        }
        return TaskResponse.from(task);
    }

    @Transactional(readOnly = true)
    public Page<TaskResponse> listTasks(UUID internshipId, TaskStatus status, Pageable pageable, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        if (actor != null && !supervisionScopeService.canManage(actor, internshipId) && !isInternOfInternship(internship, actor)) {
            throw new ResourceNotFoundException("Internship not found: " + internshipId);
        }
        // T04/D8: interns never see not-yet-visible tasks (list, counts and
        // progress all read through here). Staff keeps the complete view.
        boolean staffView = actor == null || supervisionScopeService.canManage(actor, internshipId);
        Page<Task> page;
        if (staffView) {
            page = (status != null)
                    ? taskRepository.findByInternshipIdAndStatus(internshipId, status, pageable)
                    : taskRepository.findByInternshipId(internshipId, pageable);
        } else {
            Instant now = Instant.now();
            page = (status != null)
                    ? taskRepository.findVisibleByInternshipIdAndStatus(internshipId, status, now, pageable)
                    : taskRepository.findVisibleByInternshipId(internshipId, now, pageable);
        }
        return page.map(TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<TaskResponse> listTasks(UUID internshipId, TaskStatus status, Pageable pageable) {
        return listTasks(internshipId, status, pageable, null);
    }

    @Transactional(readOnly = true)
    public Page<TaskResponse> listAllTasks(TaskStatus status, Pageable pageable, UserPrincipal actor) {
        if (supervisionScopeService.hasGlobalAccess(actor)) {
            Page<Task> page = (status != null)
                    ? taskRepository.findByStatus(status, pageable)
                    : taskRepository.findAll(pageable);
            return page.map(TaskResponse::from);
        }
        List<Internship> assigned = supervisionScopeService.assignedInternships(actor);
        if (assigned == null || assigned.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> internshipIds = assigned.stream().map(Internship::getId).toList();
        Page<Task> page = (status != null)
                ? taskRepository.findByInternshipIdInAndStatus(internshipIds, status, pageable)
                : taskRepository.findByInternshipIdIn(internshipIds, pageable);
        return page.map(TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (actor != null && !supervisionScopeService.canManage(actor, internshipId) && !isInternOfTask(task, actor)) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        ensureVisibleToIntern(task, actor, internshipId);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse updateTask(UUID taskId, TaskRequest request, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (actor != null && !supervisionScopeService.canManage(actor, internshipId) && !isInternOfTask(task, actor)) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        ensureVisibleToIntern(task, actor, internshipId);

        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.assignedToId() != null) {
            task.setAssignedTo(findUserOrThrow(request.assignedToId()));
        }
        if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }
        // T04/D8: rescheduling is a staff-only write. Null = leave the
        // current schedule unchanged (send a past instant to make a
        // scheduled task immediate); status is never touched here for that.
        if (request.visibleFrom() != null) {
            ensureStaffScheduling(actor, internshipId);
            checkVisibleFromWithinPeriod(request.visibleFrom(), task.getInternship());
            task.setVisibleFrom(request.visibleFrom());
        }
        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.COMPLETED && task.getCompletedAt() == null) {
                task.setCompletedAt(Instant.now());
            } else if (request.status() != TaskStatus.COMPLETED) {
                task.setCompletedAt(null);
            }
        }

        task = taskRepository.saveTask(task);
        // Map.of forbids nulls — unassigned tasks carry a null assignedToId.
        java.util.Map<String, Object> updateAudit = new java.util.LinkedHashMap<>();
        updateAudit.put("title", task.getTitle());
        updateAudit.put("status", task.getStatus());
        updateAudit.put("assignedToId", task.getAssignedTo() != null ? task.getAssignedTo().getId() : null);
        updateAudit.put("visibleFrom", task.getVisibleFrom());
        auditService.log("COMPANION_TASK_UPDATED", "Task", task.getId(),
                Map.of("title", task.getTitle(), "status", task.getStatus()),
                updateAudit,
                actor.getId(), null);
        publishTaskUpdated(task, actor);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse updateTaskStatus(UUID taskId, TaskStatus status, UserPrincipal actor) {
        // T06/BR-56: the offline queue replays with the client's key; the
        // scope is METHOD + URI (the target status rides the query string),
        // so the mobile client mints one fresh UUID per logical write and
        // reuses it only for retries of that same write.
        return idempotencyService.execute(actor.getId(), IdempotencyService.currentKey().orElse(null),
                () -> doUpdateTaskStatus(taskId, status, actor), TaskResponse.class);
    }

    private TaskResponse doUpdateTaskStatus(UUID taskId, TaskStatus status, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (actor != null && !supervisionScopeService.canManage(actor, internshipId) && !isInternOfTask(task, actor)) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        ensureVisibleToIntern(task, actor, internshipId);

        task.setStatus(status);
        if (status == TaskStatus.COMPLETED) {
            task.setCompletedAt(Instant.now());
        } else {
            task.setCompletedAt(null);
        }

        task = taskRepository.saveTask(task);
        // E2: completedAt is null for non-COMPLETED statuses — Map.of forbids nulls.
        java.util.Map<String, Object> statusAudit = new java.util.LinkedHashMap<>();
        statusAudit.put("status", status);
        statusAudit.put("completedAt", task.getCompletedAt());
        audit("COMPANION_TASK_STATUS_CHANGED", "Task", taskId, null,
                statusAudit, actor);
        publishTaskStatusChanged(task, actor);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse reviewTask(UUID taskId, ValidationRequest request, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (!supervisionScopeService.canManage(actor, internshipId)) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new BusinessRuleException("TASK_NOT_COMPLETED", "Only completed tasks can be reviewed.");
        }
        if (!request.approve() && (request.comment() == null || request.comment().isBlank())) {
            throw new BusinessRuleException("REVIEW_REASON_REQUIRED", "A denial reason is required.");
        }
        task.setStatus(request.approve() ? TaskStatus.APPROVED : TaskStatus.DENIED);
        task.setReviewReason(request.approve() ? null : request.comment().trim());
        task.setReviewedBy(findUserOrThrow(actor.getId()));
        task.setReviewedAt(Instant.now());
        task = taskRepository.saveTask(task);
        auditService.log(request.approve() ? "TASK_APPROVED" : "TASK_DENIED", "Task", taskId,
                null, Map.of("status", task.getStatus(), "reason", task.getReviewReason() == null ? "" : task.getReviewReason()),
                actor.getId(), null);
        publishTaskStatusChanged(task, actor);
        return TaskResponse.from(task);
    }

    @Transactional
    public BulkTaskResponse bulkTasks(List<BulkTaskMutation> mutations, UserPrincipal actor) {
        return idempotencyService.execute(actor.getId(), IdempotencyService.currentKey().orElse(null),
                () -> executeBulkTasks(mutations, actor), BulkTaskResponse.class);
    }

    private BulkTaskResponse executeBulkTasks(List<BulkTaskMutation> mutations, UserPrincipal actor) {
        if (mutations == null || mutations.isEmpty()) {
            throw new BusinessRuleException("BULK_TASKS_EMPTY", "At least one task mutation is required.");
        }
        if (mutations.size() > 100) {
            throw new BusinessRuleException("BULK_TASKS_TOO_LARGE", "A bulk request cannot exceed 100 mutations.");
        }

        List<Task> existingTasks = mutations.stream()
                .filter(mutation -> mutation.action() != BulkTaskAction.CREATE)
                .map(mutation -> taskRepository.findById(mutation.taskId())
                        .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + mutation.taskId())))
                .toList();
        for (Task task : existingTasks) {
            if (!supervisionScopeService.canManage(actor, task.getInternship().getId())) {
                throw new ResourceNotFoundException("Task not found: " + task.getId());
            }
        }
        for (BulkTaskMutation mutation : mutations) {
            if (mutation.action() == BulkTaskAction.CREATE) {
                if (mutation.internshipId() == null || mutation.task() == null
                        || mutation.task().title() == null || mutation.task().title().isBlank()) {
                    throw new BusinessRuleException("BULK_TASK_INVALID", "CREATE requires internshipId and task title.");
                }
                if (!supervisionScopeService.hasGlobalAccess(actor)
                        && !supervisionScopeService.isAssignedTo(actor, mutation.internshipId())) {
                    throw new ResourceNotFoundException("Internship not found: " + mutation.internshipId());
                }
            }
        }

        List<TaskResponse> createdOrUpdated = new java.util.ArrayList<>();
        List<BulkTaskItemResult> items = new java.util.ArrayList<>();
        int deleted = 0;
        for (int i = 0; i < mutations.size(); i++) {
            BulkTaskMutation mutation = mutations.get(i);
            if (mutation.action() == BulkTaskAction.CREATE) {
                TaskResponse created = createTask(mutation.internshipId(), mutation.task(), actor);
                createdOrUpdated.add(created);
                items.add(new BulkTaskItemResult(i, mutation.action(), created.id(),
                        mutation.internshipId(), "OK"));
            } else {
                Task task = taskRepository.findById(mutation.taskId()).orElseThrow();
                if (mutation.action() == BulkTaskAction.UPDATE) {
                    TaskResponse updated = updateTask(task.getId(), mutation.task(), actor);
                    createdOrUpdated.add(updated);
                    items.add(new BulkTaskItemResult(i, mutation.action(), updated.id(),
                            updated.internshipId(), "OK"));
                } else {
                    UUID internshipId = task.getInternship() != null
                            ? task.getInternship().getId() : null;
                    UUID taskId = task.getId();
                    taskRepository.delete(task);
                    auditService.log("COMPANION_TASK_DELETED", "Task", taskId,
                            Map.of("title", task.getTitle()), null, actor.getId(), null);
                    publishTaskDeleted(taskId, task, actor);
                    deleted++;
                    items.add(new BulkTaskItemResult(i, mutation.action(), taskId,
                            internshipId, "OK"));
                }
            }
        }
        return new BulkTaskResponse(createdOrUpdated, deleted, List.copyOf(items));
    }

    /**
     * S6c — deletes one task. Scoped like every other task write: an
     * out-of-scope id is 404, never 403, so a supervisor cannot probe another
     * supervisor's tasks.
     */
    @Transactional
    public void deleteTask(UUID taskId, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (!supervisionScopeService.canManage(actor, internshipId)) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        taskRepository.delete(task);
        auditService.log("COMPANION_TASK_DELETED", "Task", taskId,
                Map.of("title", task.getTitle()), null, actor.getId(), null);
        publishTaskDeleted(taskId, task, actor);
    }

    private void publishTaskStatusChanged(Task task, UserPrincipal actor) {
        UUID internshipId = task.getInternship().getId();
        UUID supervisorUserId = supervisionScopeService.findSupervisorUserId(internshipId).orElse(null);
        UUID internUserId = visibleInternRecipient(task);
        eventPublisher.publishEvent(new TaskStatusChangedEvent(
                task.getId(), internshipId, task.getTitle(), task.getStatus(),
                supervisorUserId, internUserId, actor.getId()));
    }

    /**
     * T06 §6: a not-yet-visible scheduled task never leaks to the intern —
     * not its title, not its existence. Staff recipients are unaffected;
     * the visibility sweep notifies the intern on appearance instead.
     */
    private UUID visibleInternRecipient(Task task) {
        if (task == null || task.getInternship() == null
                || task.getInternship().getCandidate() == null
                || task.getInternship().getCandidate().getUser() == null) {
            return null;
        }
        if (TaskCompletionPolicy.isHidden(task, Instant.now())) {
            return null;
        }
        return task.getInternship().getCandidate().getUser().getId();
    }

    /** T01: the intern + supervisor learn that a task's editable fields changed. */
    private void publishTaskUpdated(Task task, UserPrincipal actor) {
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (internshipId == null) {
            return;
        }
        UUID supervisorUserId = supervisionScopeService.findSupervisorUserId(internshipId).orElse(null);
        UUID internUserId = visibleInternRecipient(task);
        eventPublisher.publishEvent(new TaskUpdatedEvent(
                task.getId(), internshipId, task.getTitle(),
                supervisorUserId, internUserId, actor.getId()));
    }

    /**
     * T01: delete notification. Called AFTER {@code taskRepository.delete} but
     * inside the same transaction (BEFORE_COMMIT listener joins it), so a
     * rollback cannot leave a phantom "task deleted" alert. The title is read
     * from the entity before the delete.
     */
    private void publishTaskDeleted(UUID taskId, Task task, UserPrincipal actor) {
        UUID internshipId = task.getInternship() != null ? task.getInternship().getId() : null;
        if (internshipId == null) {
            return;
        }
        UUID supervisorUserId = supervisionScopeService.findSupervisorUserId(internshipId).orElse(null);
        UUID internUserId = visibleInternRecipient(task);
        eventPublisher.publishEvent(new TaskDeletedEvent(
                taskId, internshipId, task.getTitle(),
                supervisorUserId, internUserId, actor.getId()));
    }

    private boolean isInternOfInternship(Internship internship, UserPrincipal actor) {
        if (internship == null || actor == null) return false;
        return internship.getCandidate() != null
                && internship.getCandidate().getUser() != null
                && actor.getId().equals(internship.getCandidate().getUser().getId());
    }

    private boolean isInternOfTask(Task task, UserPrincipal actor) {
        if (task == null || actor == null) return false;
        return isInternOfInternship(task.getInternship(), actor);
    }

    /**
     * T04/D8: a not-yet-visible task does not exist for the intern (404, no
     * leak). Staff ({@code canManage}) and actor-less internal reads keep
     * the complete view.
     */
    private void ensureVisibleToIntern(Task task, UserPrincipal actor, UUID internshipId) {
        if (actor == null || supervisionScopeService.canManage(actor, internshipId)) {
            return;
        }
        if (TaskCompletionPolicy.isHidden(task, Instant.now())) {
            throw new ResourceNotFoundException("Task not found: " + task.getId());
        }
    }

    /**
     * T04/D8: scheduling is a staff-only write. An intern sending a non-null
     * {@code visibleFrom} is refused with 422 (not silently ignored), so a
     * manipulated client request can never schedule.
     */
    private void ensureStaffScheduling(UserPrincipal actor, UUID internshipId) {
        if (actor == null || !supervisionScopeService.canManage(actor, internshipId)) {
            throw new BusinessRuleException("TASK_SCHEDULE_STAFF_ONLY",
                    "Only the supervisor can schedule a task.");
        }
    }

    /**
     * T04/D8: the scheduled moment must fall inside the internship period
     * (application time zone). A past moment is immediate, never an error.
     */
    private void checkVisibleFromWithinPeriod(Instant visibleFrom, Internship internship) {
        if (visibleFrom == null || internship == null
                || internship.getStartDate() == null || internship.getEndDate() == null) {
            return;
        }
        java.time.LocalDate day = visibleFrom
                .atZone(applicationTimeZone.zoneId()).toLocalDate();
        if (day.isBefore(internship.getStartDate()) || day.isAfter(internship.getEndDate())) {
            throw new BusinessRuleException("VISIBLE_FROM_OUTSIDE_PERIOD",
                    "The scheduled date must be within the internship period ("
                            + internship.getStartDate() + " to " + internship.getEndDate() + ").");
        }
    }

    // -------------------------------------------------------------------------
    // Journal & Journal Entries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<JournalEntryResponse> listJournalEntries(UUID internshipId, JournalEntryStatus status,
                                                        LocalDate startDate, LocalDate endDate, Pageable pageable) {
        InternshipJournal journal = findOrCreateJournal(internshipId);
        Page<JournalEntry> page;

        if (status != null && startDate != null && endDate != null) {
            page = journalEntryRepository.findByJournalIdAndStatusAndEntryDateBetween(journal.getId(), status, startDate, endDate, pageable);
        } else if (status != null) {
            page = journalEntryRepository.findByJournalIdAndStatus(journal.getId(), status, pageable);
        } else if (startDate != null && endDate != null) {
            page = journalEntryRepository.findByJournalIdAndEntryDateBetween(journal.getId(), startDate, endDate, pageable);
        } else {
            page = journalEntryRepository.findByJournalId(journal.getId(), pageable);
        }

        return page.map(JournalEntryResponse::from);
    }

    @Transactional
    public JournalEntryResponse createJournalEntry(UUID internshipId, JournalEntryRequest request, UserPrincipal actor) {
        InternshipJournal journal = findOrCreateJournal(internshipId);
        User author = findUserOrThrow(actor.getId());

        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : LocalDate.now();
        JournalEntry entry = new JournalEntry(journal, author, request.title(), request.description(), entryDate);
        entry.setStatus(JournalEntryStatus.DRAFT);

        entry = journalEntryRepository.save(entry);
        log.info("Journal entry created: id={}, journal={}, author={}", entry.getId(), journal.getId(), author.getId());
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse submitJournalEntry(UUID entryId, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.DRAFT && entry.getStatus() != JournalEntryStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only DRAFT or REJECTED journal entries can be submitted. Current status: " + entry.getStatus());
        }

        entry.setStatus(JournalEntryStatus.SUBMITTED);
        entry.setSubmittedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry submitted: id={}", entry.getId());
        audit("JOURNAL_ENTRY_SUBMITTED", "JournalEntry", entry.getId(), null,
                Map.of("status", entry.getStatus(), "submittedAt", entry.getSubmittedAt()), actor);
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse validateJournalEntry(UUID entryId, ValidationRequest request, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED journal entries can be validated. Current status: " + entry.getStatus());
        }

        // Supervisor validation check: actor must be active supervisor or HR/ADMIN
        Employee supervisor = findSupervisorForInternship(entry.getJournal().getInternship().getId(), actor);

        entry.setStatus(JournalEntryStatus.VALIDATED);
        entry.setValidatedBy(supervisor);
        entry.setValidatedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry validated: id={}, validatedBy={}", entry.getId(), supervisor != null ? supervisor.getId() : null);
        auditService.log("JOURNAL_ENTRY_VALIDATED", "JournalEntry", entry.getId(),
                Map.of("status", JournalEntryStatus.SUBMITTED),
                reviewAudit(JournalEntryStatus.VALIDATED, null, supervisor),
                actor.getId(), null);

        // Phase A10: notify the intern author (consumed AFTER_COMMIT).
        eventPublisher.publishEvent(new JournalEntryValidatedEvent(
                entry.getId(), entry.getTitle(),
                entry.getJournal().getInternship().getId(),
                entry.getAuthor().getId(), actor.getId()));
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse rejectJournalEntry(UUID entryId, ValidationRequest request, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED journal entries can be rejected. Current status: " + entry.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(entry.getJournal().getInternship().getId(), actor);

        entry.setStatus(JournalEntryStatus.REJECTED);
        entry.setValidatedBy(supervisor);
        entry.setValidatedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry rejected: id={}, rejectedBy={}", entry.getId(), supervisor != null ? supervisor.getId() : null);
        String rejectionReason = request != null ? request.comment() : null;
        auditService.log("JOURNAL_ENTRY_REJECTED", "JournalEntry", entry.getId(),
                Map.of("status", JournalEntryStatus.SUBMITTED),
                reviewAudit(JournalEntryStatus.REJECTED, rejectionReason, supervisor),
                actor.getId(), null);

        // T10/ST-VAL-04: the reason travels in the notification itself — the
        // audit row is Admin-only, so without this the student would see
        // REJECTED with no explanation of what to fix.
        eventPublisher.publishEvent(new SupervisorDocumentRejectedEvent(
                entry.getJournal().getInternship().getId(),
                entry.getJournal().getInternship().getReference(),
                "JOURNAL_ENTRY", entry.getId(), entry.getTitle(),
                rejectionReason,
                entry.getAuthor() != null ? entry.getAuthor().getId() : null,
                actor.getId()));
        return JournalEntryResponse.from(entry);
    }

    // -------------------------------------------------------------------------
    // Deliverables
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<DeliverableResponse> listDeliverables(UUID internshipId, Pageable pageable) {
        findInternshipOrThrow(internshipId);
        Page<Deliverable> deliverables = deliverableRepository.findByInternshipId(internshipId, pageable);
        return deliverables.map(this::toDeliverableResponse);
    }

    @Transactional(readOnly = true)
    public DeliverableResponse getDeliverable(UUID deliverableId) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse createDeliverable(UUID internshipId, String title, String description,
                                                 MultipartFile file, String documentKind, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        User uploader = findUserOrThrow(actor.getId());

        // T10/B8 (D4b): the student may declare the document's identity at
        // upload — explicit data instead of the back office's order inference.
        ValidationDocumentType kind = parseDocumentKind(documentKind);

        Deliverable deliverable = new Deliverable(internship, title, description);
        deliverable.setStatus(DeliverableStatus.DRAFT);
        deliverable.setCurrentVersion(1);
        deliverable.setDocumentKind(kind);
        deliverable = deliverableRepository.save(deliverable);

        FileAsset fileAsset = storeFileAsset(file, uploader, DocumentType.STEG_INTERNSHIP_REPORT);

        DeliverableVersion version = new DeliverableVersion(deliverable, fileAsset, uploader, 1, "Initial version");
        version = deliverableVersionRepository.save(version);

        log.info("Deliverable created: id={}, v1 fileAsset={}, uploader={}, documentKind={}",
                deliverable.getId(), fileAsset.getId(), uploader.getId(), kind);
        // E2: documentKind is optional — Map.of forbids nulls.
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("internshipId", internshipId);
        created.put("title", title);
        created.put("currentVersion", 1);
        created.put("documentKind", kind != null ? kind.name() : null);
        audit("DELIVERABLE_CREATED", "Deliverable", deliverable.getId(), null, created, actor);
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse uploadNewVersion(UUID deliverableId, MultipartFile file, String changeSummary, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() == DeliverableStatus.VALIDATED) {
            throw new BusinessRuleException("DELIVERABLE_ALREADY_VALIDATED", "Cannot upload new version to an already validated deliverable.");
        }

        User uploader = findUserOrThrow(actor.getId());
        FileAsset fileAsset = storeFileAsset(file, uploader, DocumentType.STEG_INTERNSHIP_REPORT);

        int nextVersion = (deliverable.getCurrentVersion() != null ? deliverable.getCurrentVersion() : 1) + 1;
        deliverable.setCurrentVersion(nextVersion);
        deliverable = deliverableRepository.save(deliverable);

        DeliverableVersion version = new DeliverableVersion(deliverable, fileAsset, uploader, nextVersion, changeSummary);
        deliverableVersionRepository.save(version);

        log.info("Deliverable {} new version {} uploaded by {}", deliverableId, nextVersion, uploader.getId());
        // E2: changeSummary is optional — Map.of forbids nulls.
        java.util.Map<String, Object> versionAudit = new java.util.LinkedHashMap<>();
        versionAudit.put("currentVersion", nextVersion);
        versionAudit.put("changeSummary", changeSummary);
        audit("DELIVERABLE_VERSION_UPLOADED", "Deliverable", deliverableId,
                Map.of("currentVersion", nextVersion - 1),
                versionAudit, actor);
        return toDeliverableResponse(deliverable);
    }

    // -------------------------------------------------------------------------
    // Server-generated deliverables (T09/B6 journal document)
    // -------------------------------------------------------------------------

    /**
     * Stores SERVER-GENERATED bytes (our own PDF renderer) as the first version
     * of a new DRAFT deliverable. The upload pipeline's untrusted-input checks
     * (Tika sniffing, malware scan) do not apply to bytes this process just
     * rendered — the same reasoning as {@code CertificateService.storeGeneratedPdf}
     * — while the storage/version/audit path stays identical to
     * {@link #createDeliverable}. No notification is emitted: a draft is not a
     * submission (BR-48, ST-JRN-06).
     */
    @Transactional
    public DeliverableResponse registerGeneratedDeliverable(UUID internshipId, String title, String description,
                                                            byte[] bytes, String fileName, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        User uploader = findUserOrThrow(actor.getId());

        Deliverable deliverable = new Deliverable(internship, title, description);
        deliverable.setStatus(DeliverableStatus.DRAFT);
        deliverable.setCurrentVersion(1);
        deliverable = deliverableRepository.save(deliverable);

        FileAsset fileAsset = storeGeneratedBytes(bytes, fileName, uploader);
        deliverableVersionRepository.save(
                new DeliverableVersion(deliverable, fileAsset, uploader, 1, "Generated by the server"));

        log.info("Generated deliverable created: id={}, fileAsset={}, uploader={}",
                deliverable.getId(), fileAsset.getId(), uploader.getId());
        audit("DELIVERABLE_CREATED", "Deliverable", deliverable.getId(), null,
                Map.of("internshipId", internshipId, "title", title, "currentVersion", 1,
                        "generated", true), actor);
        return toDeliverableResponse(deliverable);
    }

    /**
     * Adds a new version of SERVER-GENERATED bytes to an existing DRAFT or
     * REJECTED deliverable (regeneration replaces the previous draft).
     * SUBMITTED and VALIDATED deliverables are immutable here — the caller must
     * create a new draft instead (T09 edge case), mirroring
     * {@code DELIVERABLE_ALREADY_VALIDATED} in {@link #uploadNewVersion}.
     */
    @Transactional
    public DeliverableResponse addGeneratedVersion(UUID deliverableId, byte[] bytes, String fileName,
                                                   String changeSummary, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        if (deliverable.getStatus() != DeliverableStatus.DRAFT
                && deliverable.getStatus() != DeliverableStatus.REJECTED) {
            throw new BusinessRuleException("DELIVERABLE_ALREADY_VALIDATED",
                    "Cannot regenerate a deliverable that is already " + deliverable.getStatus() + ".");
        }
        User uploader = findUserOrThrow(actor.getId());
        FileAsset fileAsset = storeGeneratedBytes(bytes, fileName, uploader);

        int nextVersion = (deliverable.getCurrentVersion() != null ? deliverable.getCurrentVersion() : 1) + 1;
        deliverable.setCurrentVersion(nextVersion);
        deliverable = deliverableRepository.save(deliverable);
        deliverableVersionRepository.save(
                new DeliverableVersion(deliverable, fileAsset, uploader, nextVersion, changeSummary));

        log.info("Generated deliverable version added: id={}, version={}", deliverableId, nextVersion);
        java.util.Map<String, Object> versionAudit = new java.util.LinkedHashMap<>();
        versionAudit.put("currentVersion", nextVersion);
        versionAudit.put("changeSummary", changeSummary);
        versionAudit.put("generated", true);
        audit("DELIVERABLE_VERSION_UPLOADED", "Deliverable", deliverableId,
                Map.of("currentVersion", nextVersion - 1), versionAudit, actor);
        return toDeliverableResponse(deliverable);
    }

    /**
     * Persists bytes produced by this backend itself: SHA-256, storage and the
     * {@link FileAsset} row, without re-validating trusted content as an
     * untrusted upload (see the callers' javadoc).
     */
    private FileAsset storeGeneratedBytes(byte[] bytes, String fileName, User uploader) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessRuleException("EMPTY_FILE", "Generated document bytes cannot be empty.");
        }
        String safeName = fileName == null || fileName.isBlank() ? "document.pdf" : fileName;
        String storageKey;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            storageKey = fileStorageService.store(is, safeName, "application/pdf");
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist generated deliverable binary", e);
        }
        return fileAssetRepository.save(new FileAsset(
                storageKey, safeName, sha256Hex(bytes), "application/pdf", (long) bytes.length, uploader));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public List<DeliverableVersionResponse> listDeliverableVersions(UUID deliverableId) {
        deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        return deliverableVersionRepository.findByDeliverableIdOrderByVersionNumberAsc(deliverableId).stream()
                .map(this::toDeliverableVersionResponse)
                .toList();
    }

    @Transactional
    public DeliverableResponse submitDeliverable(UUID deliverableId, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.DRAFT && deliverable.getStatus() != DeliverableStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only DRAFT or REJECTED deliverables can be submitted. Current status: " + deliverable.getStatus());
        }

        // T10/B7 (BR-22, ST-VAL-01): the internship's journal/report documents
        // may only be submitted inside the final week. Ordinary deliverables
        // (no declared kind) keep their existing lifecycle untouched.
        requireSubmissionWindowOpen(deliverable);

        deliverable.setStatus(DeliverableStatus.SUBMITTED);
        deliverable.setSubmittedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable submitted: id={}", deliverableId);
        audit("DELIVERABLE_SUBMITTED", "Deliverable", deliverableId, null,
                Map.of("status", DeliverableStatus.SUBMITTED, "submittedAt", deliverable.getSubmittedAt()), actor);
        advanceToReportSubmittedOnFirstSubmission(deliverable, actor);
        return toDeliverableResponse(deliverable);
    }

    /**
     * T10/B7 — the server-computed final-week submission window (BR-22) for
     * the caller's internship, so the app can show/hide the journal/report
     * submission honestly (never from the device clock, BR-21/BR-53).
     */
    @Transactional(readOnly = true)
    public SubmissionWindowResponse submissionWindow(UUID internshipId) {
        Internship internship = findInternshipOrThrow(internshipId);
        return SubmissionWindowResponse.from(submissionWindowDecision(internship));
    }

    /**
     * T10/SU-VAL-01 (D2 first-level review): the supervising side registers a
     * received document as the internship's JOURNAL or REPORT — the explicit
     * identity the Admin validation queue resolves (B8), replacing the order
     * inference for this internship. Scoped by the endpoint's
     * {@code isSupervisorOf(#deliverableId)} guard; the service re-checks the
     * row, keeps one document per kind (moving the registration clears the
     * same kind from the other non-validated documents) and refuses to change
     * the kind of a document the Admin already validated. Audited as metadata
     * only; a mobile supervisor's action carries {@code source = MOBILE}.
     */
    @Transactional
    public DeliverableResponse registerDocumentKind(UUID deliverableId, String documentKind, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        ValidationDocumentType kind = parseDocumentKind(documentKind);
        if (kind == null) {
            throw new BusinessRuleException("DOCUMENT_KIND_REQUIRED",
                    "A document kind (JOURNAL or REPORT) is required to register a validation document.");
        }
        Internship internship = deliverable.getInternship();
        if (internship == null) {
            throw new ResourceNotFoundException("Deliverable has no internship: " + deliverableId);
        }
        if (deliverable.getStatus() == DeliverableStatus.VALIDATED) {
            throw new BusinessRuleException("DELIVERABLE_ALREADY_VALIDATED",
                    "A validated document keeps its kind; send the new version as its own deliverable.");
        }

        List<Deliverable> siblings = deliverableRepository.findByInternshipId(internship.getId());
        for (Deliverable other : siblings) {
            if (other.getId().equals(deliverable.getId()) || other.getDocumentKind() != kind) {
                continue;
            }
            if (other.getStatus() == DeliverableStatus.VALIDATED) {
                throw new BusinessRuleException("DOCUMENT_KIND_LOCKED",
                        "The " + kind + " document of this internship is already validated.");
            }
            other.setDocumentKind(null);
            deliverableRepository.save(other);
        }

        ValidationDocumentType previous = deliverable.getDocumentKind();
        deliverable.setDocumentKind(kind);
        deliverable = deliverableRepository.save(deliverable);

        log.info("Validation document registered: deliverableId={}, kind={} (previous={}), actor={}",
                deliverableId, kind, previous, actor != null ? actor.getId() : null);
        Map<String, Object> registered = new LinkedHashMap<>();
        registered.put("internshipId", internship.getId());
        registered.put("documentKind", kind.name());
        registered.put("previousKind", previous != null ? previous.name() : null);
        registered.put("status", deliverable.getStatus() != null ? deliverable.getStatus().name() : null);
        audit("VALIDATION_DOCUMENT_REGISTERED", "Deliverable", deliverableId, null, registered, actor);
        return toDeliverableResponse(deliverable);
    }

    /** Parses the optional/required document kind; unknown values are a coded 422. */
    private static ValidationDocumentType parseDocumentKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ValidationDocumentType.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_DOCUMENT_KIND",
                    "Unknown document kind '" + raw + "'. Allowed values: REPORT, JOURNAL.");
        }
    }

    /**
     * T10/B7: a document declared as the journal or the report may only be
     * submitted inside the final week (BR-22); late is refused, not accepted.
     * A document without a declared kind is not a validation document and
     * keeps the pre-existing lifecycle.
     */
    private void requireSubmissionWindowOpen(Deliverable deliverable) {
        if (deliverable.getDocumentKind() == null) {
            return;
        }
        SubmissionWindowPolicy.Decision decision = submissionWindowDecision(deliverable.getInternship());
        if (decision.open()) {
            return;
        }
        String detail = switch (decision.reason()) {
            case BEFORE_WINDOW -> "The final-week submission window opens on " + decision.opensAt()
                    + " (BR-22: the last week of the internship).";
            case AFTER_WINDOW -> "The final-week submission window closed on " + decision.closesAt()
                    + ". Contact your supervisor.";
            case NO_PERIOD -> "The internship period is incomplete, so no submission window can be evaluated.";
            case CANCELLED -> "This internship is cancelled; validation documents cannot be submitted.";
            default -> "The submission window is not open.";
        };
        throw new BusinessRuleException("SUBMISSION_NOT_IN_WINDOW", detail);
    }

    private SubmissionWindowPolicy.Decision submissionWindowDecision(Internship internship) {
        if (internship == null) {
            return new SubmissionWindowPolicy.Decision(false, null, null,
                    SubmissionWindowPolicy.Reason.NO_PERIOD, 0, 0);
        }
        return SubmissionWindowPolicy.evaluate(applicationTimeZone.today(),
                internship.getStartDate(), internship.getEndDate(),
                internship.getStatus() == InternshipStatus.CANCELLED);
    }

    /**
     * S7a.1 (assumption A8): the deliverables channel IS the report/journal
     * submission step. The first submission while the internship is IN_PROGRESS
     * moves it to REPORT_SUBMITTED through the single authority (supervisor +
     * intern are notified by the lifecycle event itself) and fans out to the
     * Admin role, which owns the validation queue. Later submissions (already
     * REPORT_SUBMITTED or beyond, e.g. resubmission after a REJECTED decision)
     * change nothing — no error, no duplicate transition.
     */
    private void advanceToReportSubmittedOnFirstSubmission(
            tn.steg.backend.companion.domain.model.Deliverable deliverable, UserPrincipal actor) {
        Internship internship = deliverable.getInternship();
        if (internship == null
                || internship.getStatus() != tn.steg.backend.internship.domain.model.InternshipStatus.IN_PROGRESS) {
            return;
        }
        internshipLifecycleService.transition(internship.getId(),
                tn.steg.backend.internship.domain.model.InternshipStatus.REPORT_SUBMITTED,
                "Report submitted by " + actor.getEmail(), actor);
        eventPublisher.publishEvent(new InternshipReportSubmittedEvent(
                internship.getId(), internship.getReference(), deliverable.getId(), actor.getId()));
    }

    @Transactional
    public DeliverableResponse validateDeliverable(UUID deliverableId, ValidationRequest request, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED deliverables can be validated. Current status: " + deliverable.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(deliverable.getInternship().getId(), actor);

        deliverable.setStatus(DeliverableStatus.VALIDATED);
        deliverable.setValidatedBy(supervisor);
        deliverable.setValidatedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable validated: id={}, validatedBy={}", deliverableId, supervisor != null ? supervisor.getId() : null);
        auditService.log("DELIVERABLE_VALIDATED", "Deliverable", deliverableId,
                Map.of("status", DeliverableStatus.SUBMITTED),
                reviewAudit(DeliverableStatus.VALIDATED, null, supervisor), actor.getId(), null);
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse rejectDeliverable(UUID deliverableId, ValidationRequest request, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED deliverables can be rejected. Current status: " + deliverable.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(deliverable.getInternship().getId(), actor);

        deliverable.setStatus(DeliverableStatus.REJECTED);
        deliverable.setValidatedBy(supervisor);
        deliverable.setValidatedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable rejected: id={}, rejectedBy={}", deliverableId, supervisor != null ? supervisor.getId() : null);
        String deliverableRejectionReason = request != null ? request.comment() : null;
        auditService.log("DELIVERABLE_REJECTED", "Deliverable", deliverableId,
                Map.of("status", DeliverableStatus.SUBMITTED),
                reviewAudit(DeliverableStatus.REJECTED, deliverableRejectionReason, supervisor), actor.getId(), null);

        // T10/ST-VAL-04: same reason-in-notification rule as journal entries.
        var rejectedInternship = deliverable.getInternship();
        eventPublisher.publishEvent(new SupervisorDocumentRejectedEvent(
                rejectedInternship.getId(),
                rejectedInternship.getReference(),
                "DELIVERABLE", deliverableId, deliverable.getTitle(),
                deliverableRejectionReason,
                rejectedInternship.getCandidate() != null
                        && rejectedInternship.getCandidate().getUser() != null
                        ? rejectedInternship.getCandidate().getUser().getId() : null,
                actor.getId()));
        return toDeliverableResponse(deliverable);
    }

    @Transactional(readOnly = true)
    public DocumentService.DownloadStream downloadDeliverableVersion(UUID deliverableId, Integer versionNumber, UserPrincipal actor) {
        deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        DeliverableVersion version;
        if (versionNumber != null) {
            version = deliverableVersionRepository.findByDeliverableIdAndVersionNumber(deliverableId, versionNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Deliverable version not found: v" + versionNumber));
        } else {
            version = deliverableVersionRepository.findTopByDeliverableIdOrderByVersionNumberDesc(deliverableId)
                    .orElseThrow(() -> new ResourceNotFoundException("No versions found for deliverable: " + deliverableId));
        }

        FileAsset fa = version.getFile();
        InputStream is = fileStorageService.getInputStream(fa.getStorageKey());
        auditService.log("DELIVERABLE_VERSION_DOWNLOADED", "Deliverable", deliverableId, null,
                Map.of("versionNumber", version.getVersionNumber(), "fileName", fa.getOriginalFileName()),
                actor.getId(), null);
        return new DocumentService.DownloadStream(is, fa.getOriginalFileName(), fa.getMimeType(), fa.getSize());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InternshipJournal findOrCreateJournal(UUID internshipId) {
        Internship internship = findInternshipOrThrow(internshipId);
        return journalRepository.findByInternshipId(internshipId)
                .orElseGet(() -> journalRepository.save(new InternshipJournal(internship)));
    }

    private Internship findInternshipOrThrow(UUID internshipId) {
        return internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private Employee findSupervisorForInternship(UUID internshipId, UserPrincipal actor) {
        InternshipAssignment assignment = assignmentRepository
                .findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .orElse(null);

        if (assignment != null && assignment.getSupervisor() != null) {
            return assignment.getSupervisor();
        }

        return employeeRepository.findByUserId(actor.getId()).orElse(null);
    }

    /**
     * Null-safe review audit payload: the supervisor has no Employee row when
     * a user-backed supervisor (e.g. a seeded demo account) validates, and
     * {@code Map.of} throws on null values. Omits absent keys instead.
     */
    private static Map<String, Object> reviewAudit(Object status, String reason, Employee supervisor) {
        // Null-safe shared helper (the ORIGINAL bug site: Map.of threw NPE when
        // a bare supervisor had no employee row and turned the review into a 500).
        return NullSafe.mapOf(
                "status", status,
                "reason", reason,
                "validatedBy", supervisor != null ? supervisor.getId() : null);
    }

    private FileAsset storeFileAsset(MultipartFile file, User uploader, DocumentType docType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded deliverable file cannot be empty.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_READ_ERROR", "Could not read deliverable file: " + e.getMessage());
        }

        DocumentValidationService.ValidationResult validation =
                documentValidationService.validate(bytes, file.getOriginalFilename(), file.getContentType(), docType);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            if (!malwareScanner.isClean(is, file.getOriginalFilename())) {
                throw new BusinessRuleException("MALWARE_DETECTED", "Malware or suspicious content detected in deliverable file.");
            }
        } catch (IOException e) {
            log.error("Malware scanner error: {}", e.getMessage());
        }

        String storageKey;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            storageKey = fileStorageService.store(is, file.getOriginalFilename(), validation.detectedMimeType());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist deliverable binary", e);
        }

        FileAsset fileAsset = new FileAsset(
                storageKey,
                file.getOriginalFilename(),
                validation.checksum(),
                validation.detectedMimeType(),
                validation.sizeBytes(),
                uploader
        );
        return fileAssetRepository.save(fileAsset);
    }

    private DeliverableResponse toDeliverableResponse(Deliverable deliverable) {
        DeliverableVersion latest = deliverableVersionRepository.findTopByDeliverableIdOrderByVersionNumberDesc(deliverable.getId()).orElse(null);
        DeliverableVersionResponse latestDto = latest != null ? toDeliverableVersionResponse(latest) : null;
        return DeliverableResponse.from(deliverable, latestDto);
    }

    private DeliverableVersionResponse toDeliverableVersionResponse(DeliverableVersion v) {
        String uploadedByEmail = v.getUploadedBy() != null ? v.getUploadedBy().getEmail() : null;
        FileAsset fa = v.getFile();
        return new DeliverableVersionResponse(
                v.getId(),
                v.getDeliverable() != null ? v.getDeliverable().getId() : null,
                fa != null ? fa.getId() : null,
                fa != null ? fa.getOriginalFileName() : null,
                fa != null ? fa.getMimeType() : null,
                fa != null ? fa.getSize() : null,
                v.getUploadedBy() != null ? v.getUploadedBy().getId() : null,
                uploadedByEmail,
                v.getVersionNumber(),
                v.getChangeSummary(),
                v.getUploadedAt()
        );
    }
}
