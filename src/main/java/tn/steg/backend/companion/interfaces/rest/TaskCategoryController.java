package tn.steg.backend.companion.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.TaskCategoryService;
import tn.steg.backend.companion.application.dto.ApplyTaskCategoriesRequest;
import tn.steg.backend.companion.application.dto.ApplyTaskCategoriesResponse;
import tn.steg.backend.companion.application.dto.AssignTaskCategoryRequest;
import tn.steg.backend.companion.application.dto.CreateTaskCategoryRequest;
import tn.steg.backend.companion.application.dto.RenameTaskCategoryRequest;
import tn.steg.backend.companion.application.dto.ReorderTaskCategoriesRequest;
import tn.steg.backend.companion.application.dto.SuggestTaskCategoriesResponse;
import tn.steg.backend.companion.application.dto.TaskCategoryBoardResponse;
import tn.steg.backend.companion.application.dto.TaskCategoryResponse;
import tn.steg.backend.companion.application.dto.UndoApplyBatchResponse;

import java.util.List;
import java.util.UUID;

/**
 * T03 student task classification (ST-TASK-03/04/05, D5/D5b).
 *
 * <p>Every endpoint is INTERN-only: categories are the student's private
 * organisation tool (a supervisor or admin token gets 403), and inside the
 * service every id resolves through the caller's own internship — a foreign
 * id is 404, never 403 (BR-08). Classification never changes task status.
 */
@RestController
@RequestMapping("/api/internships")
@RequiredArgsConstructor
@Tag(name = "Task Classification", description = "T03 student-defined task categories + AI proposals (intern only)")
public class TaskCategoryController {

    private final TaskCategoryService categoryService;

    @GetMapping("/{id}/task-categories")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Load my classification board (categories + assignments for one internship)")
    public ResponseEntity<TaskCategoryBoardResponse> board(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.board(actor, id));
    }

    @PostMapping("/{id}/task-categories")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Create one of my categories (name unique per student, at most 20)")
    public ResponseEntity<TaskCategoryResponse> create(
            @PathVariable UUID id,
            @RequestBody(required = false) CreateTaskCategoryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categoryService.create(actor, id, request));
    }

    @PutMapping("/task-categories/{categoryId}")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Rename one of my categories (owner only, 404 otherwise)")
    public ResponseEntity<TaskCategoryResponse> rename(
            @PathVariable UUID categoryId,
            @RequestBody(required = false) RenameTaskCategoryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.rename(actor, categoryId, request));
    }

    @PutMapping("/task-categories/order")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Reorder my categories (must contain exactly my current categories)")
    public ResponseEntity<List<TaskCategoryResponse>> reorder(
            @RequestBody(required = false) ReorderTaskCategoriesRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.reorder(actor, request));
    }

    @DeleteMapping("/task-categories/{categoryId}")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Delete one of my categories (its tasks become unclassified, nothing is lost)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID categoryId,
            @AuthenticationPrincipal UserPrincipal actor) {
        categoryService.delete(actor, categoryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tasks/{taskId}/category")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Assign or clear my task's category (compare-and-set, status untouched)")
    public ResponseEntity<Void> assign(
            @PathVariable UUID taskId,
            @RequestBody(required = false) AssignTaskCategoryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        categoryService.assign(actor, taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/task-categories/suggest")
    @PreAuthorize("hasRole('INTERN')")
    @RateLimited(name = "ai-classify", limit = 10, windowSeconds = 60)
    @Operation(summary = "AI classification proposals for my unclassified tasks (proposals only, persists nothing)")
    public ResponseEntity<SuggestTaskCategoriesResponse> suggest(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.suggest(actor, id));
    }

    @PostMapping("/{id}/task-categories/apply")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Accept classifications (per-item compare-and-set, per-item results, idempotent)",
            description = "Per-item, not all-or-nothing: a task classified or deleted in the meantime is "
                    + "reported per item (skipped: already classified / not found) and is never overwritten. "
                    + "Send X-Idempotency-Key to make a double submit replay without duplicating.")
    public ResponseEntity<ApplyTaskCategoriesResponse> apply(
            @PathVariable UUID id,
            @RequestBody(required = false) ApplyTaskCategoriesRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.apply(actor, id, request));
    }

    @PostMapping("/task-categories/apply-batches/{batchId}/undo")
    @PreAuthorize("hasRole('INTERN')")
    @Operation(summary = "Undo an accepted batch (restores only tasks unchanged since the batch)")
    public ResponseEntity<UndoApplyBatchResponse> undo(
            @PathVariable UUID batchId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(categoryService.undo(actor, batchId));
    }
}
