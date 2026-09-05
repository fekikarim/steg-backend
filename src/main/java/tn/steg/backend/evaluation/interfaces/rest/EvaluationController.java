package tn.steg.backend.evaluation.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.evaluation.application.EvaluationService;
import tn.steg.backend.evaluation.application.dto.*;
import tn.steg.backend.evaluation.domain.model.EvaluationType;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Evaluations", description = "Endpoints for Internship Evaluations, Scores, and Task Reviews")
public class EvaluationController {

    private final EvaluationService evaluationService;

    // -------------------------------------------------------------------------
    // Evaluations
    // -------------------------------------------------------------------------

    @PostMapping("/internships/{internshipId}/evaluations")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isSupervisorOf(#internshipId)")
    @Operation(summary = "Create an evaluation for an internship (Supervisor only)")
    public ResponseEntity<EvaluationResponse> createEvaluation(
            @PathVariable UUID internshipId,
            @Valid @RequestBody EvaluationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluationService.createEvaluation(internshipId, request, actor));
    }

    @GetMapping("/internships/{internshipId}/evaluations")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#internshipId)")
    @Operation(summary = "List evaluations for an internship (paginated)")
    public ResponseEntity<Page<EvaluationResponse>> listEvaluations(
            @PathVariable UUID internshipId,
            @RequestParam(required = false) EvaluationType type,
            @PageableDefault(size = 20, sort = "evaluationDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(evaluationService.listEvaluations(internshipId, type, pageable));
    }

    @GetMapping("/evaluations/{evaluationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#evaluationId)")
    @Operation(summary = "Get evaluation details by ID")
    public ResponseEntity<EvaluationResponse> getEvaluation(@PathVariable UUID evaluationId) {
        return ResponseEntity.ok(evaluationService.getEvaluation(evaluationId));
    }

    // -------------------------------------------------------------------------
    // Evaluation Scores
    // -------------------------------------------------------------------------

    @PostMapping("/evaluations/{evaluationId}/scores")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isSupervisorOf(#evaluationId)")
    @Operation(summary = "Submit/update criteria scores for an evaluation (Supervisor only)")
    public ResponseEntity<List<EvaluationScoreResponse>> submitScores(
            @PathVariable UUID evaluationId,
            @Valid @RequestBody List<EvaluationScoreRequest> requests,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(evaluationService.submitScores(evaluationId, requests, actor));
    }

    @GetMapping("/evaluations/{evaluationId}/scores")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#evaluationId)")
    @Operation(summary = "List criteria scores for an evaluation")
    public ResponseEntity<List<EvaluationScoreResponse>> listScores(@PathVariable UUID evaluationId) {
        return ResponseEntity.ok(evaluationService.listScores(evaluationId));
    }

    // -------------------------------------------------------------------------
    // Task Reviews
    // -------------------------------------------------------------------------

    @PostMapping("/evaluations/{evaluationId}/task-reviews")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isSupervisorOf(#evaluationId)")
    @Operation(summary = "Add task review to an evaluation (Supervisor only)")
    public ResponseEntity<EvaluationTaskReviewResponse> addTaskReview(
            @PathVariable UUID evaluationId,
            @Valid @RequestBody EvaluationTaskReviewRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluationService.addTaskReview(evaluationId, request, actor));
    }

    @GetMapping("/evaluations/{evaluationId}/task-reviews")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#evaluationId)")
    @Operation(summary = "List task reviews for an evaluation")
    public ResponseEntity<List<EvaluationTaskReviewResponse>> listTaskReviews(@PathVariable UUID evaluationId) {
        return ResponseEntity.ok(evaluationService.listTaskReviews(evaluationId));
    }
}
