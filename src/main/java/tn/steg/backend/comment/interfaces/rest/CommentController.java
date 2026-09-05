package tn.steg.backend.comment.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.comment.application.CommentService;
import tn.steg.backend.comment.application.dto.CommentRequest;
import tn.steg.backend.comment.application.dto.CommentResponse;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "Endpoints for Journal Entry, Deliverable, and Evaluation comments")
public class CommentController {

    private final CommentService commentService;

    // -------------------------------------------------------------------------
    // Journal Entry Comments
    // -------------------------------------------------------------------------

    @PostMapping("/journal/entries/{entryId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#entryId)")
    @Operation(summary = "Add comment to a journal entry")
    public ResponseEntity<CommentResponse> addJournalEntryComment(
            @PathVariable UUID entryId,
            @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addJournalEntryComment(entryId, request, actor));
    }

    @GetMapping("/journal/entries/{entryId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#entryId)")
    @Operation(summary = "List comments for a journal entry")
    public ResponseEntity<List<CommentResponse>> getJournalEntryComments(
            @PathVariable UUID entryId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(commentService.getJournalEntryComments(entryId, actor));
    }

    // -------------------------------------------------------------------------
    // Deliverable Comments
    // -------------------------------------------------------------------------

    @PostMapping("/deliverables/{deliverableId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#deliverableId)")
    @Operation(summary = "Add comment to a deliverable")
    public ResponseEntity<CommentResponse> addDeliverableComment(
            @PathVariable UUID deliverableId,
            @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addDeliverableComment(deliverableId, request, actor));
    }

    @GetMapping("/deliverables/{deliverableId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#deliverableId)")
    @Operation(summary = "List comments for a deliverable")
    public ResponseEntity<List<CommentResponse>> getDeliverableComments(
            @PathVariable UUID deliverableId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(commentService.getDeliverableComments(deliverableId, actor));
    }

    // -------------------------------------------------------------------------
    // Evaluation Comments
    // -------------------------------------------------------------------------

    @PostMapping("/evaluations/{evaluationId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#evaluationId)")
    @Operation(summary = "Add comment to an evaluation")
    public ResponseEntity<CommentResponse> addEvaluationComment(
            @PathVariable UUID evaluationId,
            @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addEvaluationComment(evaluationId, request, actor));
    }

    @GetMapping("/evaluations/{evaluationId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#evaluationId)")
    @Operation(summary = "List comments for an evaluation")
    public ResponseEntity<List<CommentResponse>> getEvaluationComments(
            @PathVariable UUID evaluationId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(commentService.getEvaluationComments(evaluationId, actor));
    }
}
