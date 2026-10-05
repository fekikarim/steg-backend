package tn.steg.backend.companion.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.CompanionService;
import tn.steg.backend.companion.application.dto.*;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.document.application.DocumentService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/internships")
@RequiredArgsConstructor
@Tag(name = "Companion", description = "Endpoints for Tasks, Journal Entries, and Deliverables")
public class CompanionController {

    private final CompanionService companionService;

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'INTERN', 'CANDIDATE')")
    @Operation(summary = "Create task for internship (Admin, assigned Supervisor, or owning intern)")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable UUID id,
            @RequestBody TaskRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companionService.createTask(id, request, actor));
    }

    @GetMapping("/{id}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'INTERN', 'CANDIDATE')")
    @Operation(summary = "List tasks for internship (paginated)")
    public ResponseEntity<Page<TaskResponse>> listTasks(
            @PathVariable UUID id,
            @RequestParam(required = false) TaskStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.listTasks(id, status, pageable, actor));
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "List all tasks globally (Admin) or within supervision scope (Supervisor)")
    public ResponseEntity<Page<TaskResponse>> listAllTasks(
            @RequestParam(required = false) TaskStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.listAllTasks(status, pageable, actor));
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'INTERN', 'CANDIDATE')")
    @Operation(summary = "Get task details (Admin, assigned Supervisor, or owning intern)")
    public ResponseEntity<TaskResponse> getTask(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.getTask(taskId, actor));
    }

    @PutMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'INTERN', 'CANDIDATE')")
    @Operation(summary = "Update task details (Admin, assigned Supervisor, or owning intern)")
    public ResponseEntity<TaskResponse> updateTask(
            @PathVariable UUID taskId,
            @RequestBody TaskRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.updateTask(taskId, request, actor));
    }

    @PatchMapping("/tasks/{taskId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'INTERN', 'CANDIDATE')")
    @Operation(summary = "Update task status (Admin, assigned Supervisor, or owning intern)")
    public ResponseEntity<TaskResponse> updateTaskStatus(
            @PathVariable UUID taskId,
            @RequestParam TaskStatus status,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.updateTaskStatus(taskId, status, actor));
    }

    @PostMapping("/tasks/{taskId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Approve or deny a completed task")
    public ResponseEntity<TaskResponse> reviewTask(
            @PathVariable UUID taskId,
            @RequestBody ValidationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.reviewTask(taskId, request, actor));
    }

    @PostMapping("/tasks/bulk")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Apply an atomic, idempotent bulk task mutation across multiple students",
            description = "All-or-nothing: every mutation is validated (scope + payload) before anything is written. "
                    + "The response carries a per-item result summary in request order. "
                    + "Send X-Idempotency-Key to make a double submit return the stored result without duplicating.")
    public ResponseEntity<BulkTaskResponse> bulkTasks(
            @RequestBody List<BulkTaskMutation> mutations,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.bulkTasks(mutations, actor));
    }

    @DeleteMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Delete one task (Admin or assigned Supervisor; out-of-scope is 404)")
    public ResponseEntity<Void> deleteTask(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal actor) {
        companionService.deleteTask(taskId, actor);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Journal Entries
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/journal/entries")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#id)")
    @Operation(summary = "List journal entries for internship (paginated, with date/status filters)")
    public ResponseEntity<Page<JournalEntryResponse>> listJournalEntries(
            @PathVariable UUID id,
            @RequestParam(required = false) JournalEntryStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(size = 20, sort = "entryDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(companionService.listJournalEntries(id, status, startDate, endDate, pageable));
    }

    @PostMapping("/{id}/journal/entries")
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#id)")
    @Operation(summary = "Create journal entry (Draft)")
    public ResponseEntity<JournalEntryResponse> createJournalEntry(
            @PathVariable UUID id,
            @RequestBody JournalEntryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companionService.createJournalEntry(id, request, actor));
    }

    @PostMapping("/journal/entries/{entryId}/submit")
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#entryId)")
    @Operation(summary = "Submit journal entry (DRAFT/REJECTED -> SUBMITTED)")
    public ResponseEntity<JournalEntryResponse> submitJournalEntry(
            @PathVariable UUID entryId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.submitJournalEntry(entryId, actor));
    }

    @PostMapping("/journal/entries/{entryId}/validate")
    @PreAuthorize("hasRole('ADMIN') or @authz.isSupervisorOf(#entryId)")
    @Operation(summary = "Validate journal entry (Supervisor only)")
    public ResponseEntity<JournalEntryResponse> validateJournalEntry(
            @PathVariable UUID entryId,
            @RequestBody(required = false) ValidationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.validateJournalEntry(entryId, request, actor));
    }

    @PostMapping("/journal/entries/{entryId}/reject")
    @PreAuthorize("hasRole('ADMIN') or @authz.isSupervisorOf(#entryId)")
    @Operation(summary = "Reject journal entry (Supervisor only)")
    public ResponseEntity<JournalEntryResponse> rejectJournalEntry(
            @PathVariable UUID entryId,
            @RequestBody(required = false) ValidationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.rejectJournalEntry(entryId, request, actor));
    }

    // -------------------------------------------------------------------------
    // Deliverables
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/deliverables")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#id)")
    @Operation(summary = "List deliverables for internship (paginated)")
    public ResponseEntity<Page<DeliverableResponse>> listDeliverables(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(companionService.listDeliverables(id, pageable));
    }

    @GetMapping("/deliverables/{deliverableId}")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#deliverableId)")
    @Operation(summary = "Get deliverable details")
    public ResponseEntity<DeliverableResponse> getDeliverable(@PathVariable UUID deliverableId) {
        return ResponseEntity.ok(companionService.getDeliverable(deliverableId));
    }

    @PostMapping(value = "/{id}/deliverables", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#id)")
    @Operation(summary = "Create deliverable with initial file (v1)")
    public ResponseEntity<DeliverableResponse> createDeliverable(
            @PathVariable UUID id,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(companionService.createDeliverable(id, title, description, file, actor));
    }

    @PostMapping(value = "/deliverables/{deliverableId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#deliverableId)")
    @Operation(summary = "Upload new version of deliverable")
    public ResponseEntity<DeliverableResponse> uploadNewVersion(
            @PathVariable UUID deliverableId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changeSummary", required = false) String changeSummary,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.uploadNewVersion(deliverableId, file, changeSummary, actor));
    }

    @GetMapping("/deliverables/{deliverableId}/versions")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#deliverableId)")
    @Operation(summary = "List all versions of a deliverable")
    public ResponseEntity<List<DeliverableVersionResponse>> listDeliverableVersions(@PathVariable UUID deliverableId) {
        return ResponseEntity.ok(companionService.listDeliverableVersions(deliverableId));
    }

    @PostMapping("/deliverables/{deliverableId}/submit")
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#deliverableId)")
    @Operation(summary = "Submit deliverable (DRAFT/REJECTED -> SUBMITTED)")
    public ResponseEntity<DeliverableResponse> submitDeliverable(
            @PathVariable UUID deliverableId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.submitDeliverable(deliverableId, actor));
    }

    @PostMapping("/deliverables/{deliverableId}/validate")
    @PreAuthorize("hasRole('ADMIN') or @authz.isSupervisorOf(#deliverableId)")
    @Operation(summary = "Validate deliverable (Supervisor only)")
    public ResponseEntity<DeliverableResponse> validateDeliverable(
            @PathVariable UUID deliverableId,
            @RequestBody(required = false) ValidationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.validateDeliverable(deliverableId, request, actor));
    }

    @PostMapping("/deliverables/{deliverableId}/reject")
    @PreAuthorize("hasRole('ADMIN') or @authz.isSupervisorOf(#deliverableId)")
    @Operation(summary = "Reject deliverable (Supervisor only)")
    public ResponseEntity<DeliverableResponse> rejectDeliverable(
            @PathVariable UUID deliverableId,
            @RequestBody(required = false) ValidationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(companionService.rejectDeliverable(deliverableId, request, actor));
    }

    @GetMapping("/deliverables/{deliverableId}/download")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#deliverableId)")
    @Operation(summary = "Download deliverable file (latest or specified version)")
    public ResponseEntity<InputStreamResource> downloadDeliverable(
            @PathVariable UUID deliverableId,
            @RequestParam(required = false) Integer version,
            @AuthenticationPrincipal UserPrincipal actor) {
        DocumentService.DownloadStream stream = companionService.downloadDeliverableVersion(deliverableId, version, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .body(new InputStreamResource(stream.inputStream()));
    }
}
