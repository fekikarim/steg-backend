package tn.steg.backend.companion.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.AiTaskDraftService;
import tn.steg.backend.companion.application.dto.BulkAddDraftsRequest;
import tn.steg.backend.companion.application.dto.BulkAddDraftsResponse;
import tn.steg.backend.companion.application.dto.GenerateSpecTextRequest;
import tn.steg.backend.companion.application.dto.ManualDraftRequest;
import tn.steg.backend.companion.application.dto.ReviseDraftRequest;
import tn.steg.backend.companion.application.dto.TaskDraftResponse;
import tn.steg.backend.companion.application.dto.UpdateDraftRequest;

import java.util.List;
import java.util.UUID;

/**
 * S10a AI task drafts (AGENTS.md §7.4, backend only).
 *
 * <p>Drafts are server-side proposals — not real tasks until the approved
 * drafts are bulk-added to one or more students. Admin may use any student;
 * a Supervisor only his own (out-of-scope → 404, never 403).
 */
@RestController
@RequestMapping("/api/internships/tasks/drafts")
@RequiredArgsConstructor
@Tag(name = "AI Task Drafts", description = "Gemini-generated task drafts (S10a): generate, review, bulk-add")
public class TaskDraftController {

    private final AiTaskDraftService draftService;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @RateLimited(name = "ai-task-generate", limit = 10, windowSeconds = 60)
    @Operation(summary = "Generate task drafts from a specifications PDF",
            description = "Uploads a specifications document; the backend extracts the text and asks "
                    + "Gemini for tasks under a strict JSON schema (validated: schema, lengths, "
                    + "dates inside the internship period; retried once when malformed). "
                    + "The result is server-side DRAFTS — no real task is created. "
                    + "Admin: any student. Supervisor: own students only (404 otherwise).")
    public ResponseEntity<List<TaskDraftResponse>> generate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("internshipId") UUID internshipId,
            @AuthenticationPrincipal UserPrincipal actor) throws java.io.IOException {
        byte[] bytes = file == null ? new byte[0] : file.getBytes();
        String filename = file == null ? null : file.getOriginalFilename();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(draftService.generateFromSpecPdf(actor, internshipId, bytes, filename));
    }

    @PostMapping("/generate-from-text")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @RateLimited(name = "ai-task-generate", limit = 10, windowSeconds = 60)
    @Operation(summary = "Generate task drafts from pasted specification text",
            description = "Same pipeline as the PDF path (strict JSON schema, "
                    + "validated lengths and dates inside the internship period, "
                    + "retried once when malformed, metadata-only audit) fed by "
                    + "a pasted text instead of an upload. The text is data, "
                    + "never instructions. The result is server-side DRAFTS — "
                    + "no real task is created. Admin: any student. "
                    + "Supervisor: own students only (404 otherwise).")
    public ResponseEntity<List<TaskDraftResponse>> generateFromText(
            @Valid @RequestBody GenerateSpecTextRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(draftService.generateFromSpecText(
                        actor, request.internshipId(), request.specText()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "List my drafts (optionally for one reference internship)")
    public ResponseEntity<List<TaskDraftResponse>> listMine(
            @RequestParam(required = false) UUID internshipId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(draftService.listMyDrafts(actor, internshipId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Add a draft manually (works even when AI is unavailable)")
    public ResponseEntity<TaskDraftResponse> addManually(
            @Valid @RequestBody ManualDraftRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(draftService.addManually(actor, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Edit a draft manually (owner only, 404 otherwise)")
    public ResponseEntity<TaskDraftResponse> updateManually(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDraftRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(draftService.updateManually(actor, id, request));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @RateLimited(name = "ai-task-revise", limit = 20, windowSeconds = 60)
    @Operation(summary = "Revise a draft with AI from a free-text instruction (owner only)")
    public ResponseEntity<TaskDraftResponse> reviseWithAi(
            @PathVariable UUID id,
            @Valid @RequestBody ReviseDraftRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(draftService.reviseWithAi(actor, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Delete a draft (owner only, 404 otherwise)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        draftService.delete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-add")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Atomically bulk-add approved drafts to one or more students",
            description = "All-or-nothing: every draft-student pair is validated (ownership, "
                    + "role scope, dates inside each target's period) before anything is written. "
                    + "A supervisor request with an out-of-scope student fails with 404. "
                    + "Send X-Idempotency-Key to make a double submit replay without duplicating.")
    public ResponseEntity<BulkAddDraftsResponse> bulkAdd(
            @Valid @RequestBody BulkAddDraftsRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(draftService.bulkAdd(actor, request));
    }
}
