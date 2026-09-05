package tn.steg.backend.evaluation.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.evaluation.application.EvaluationService;
import tn.steg.backend.evaluation.application.dto.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/evaluation-templates")
@RequiredArgsConstructor
@Tag(name = "Evaluation Templates", description = "Endpoints for managing Evaluation Templates and Criteria (HR/ADMIN)")
public class EvaluationTemplateController {

    private final EvaluationService evaluationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @Operation(summary = "List evaluation templates")
    public ResponseEntity<List<EvaluationTemplateResponse>> listTemplates(
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(evaluationService.listTemplates(activeOnly));
    }

    @GetMapping("/{templateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @Operation(summary = "Get evaluation template by ID")
    public ResponseEntity<EvaluationTemplateResponse> getTemplate(@PathVariable UUID templateId) {
        return ResponseEntity.ok(evaluationService.getTemplate(templateId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Create evaluation template (HR/ADMIN only)")
    public ResponseEntity<EvaluationTemplateResponse> createTemplate(
            @Valid @RequestBody EvaluationTemplateRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluationService.createTemplate(request, actor));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Update evaluation template (HR/ADMIN only)")
    public ResponseEntity<EvaluationTemplateResponse> updateTemplate(
            @PathVariable UUID templateId,
            @Valid @RequestBody EvaluationTemplateRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(evaluationService.updateTemplate(templateId, request, actor));
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Deactivate evaluation template (HR/ADMIN only)")
    public ResponseEntity<Void> deactivateTemplate(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal actor) {
        evaluationService.deactivateTemplate(templateId, actor);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Criteria Endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{templateId}/criteria")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @Operation(summary = "List criteria of an evaluation template")
    public ResponseEntity<List<EvaluationCriterionResponse>> listCriteria(@PathVariable UUID templateId) {
        return ResponseEntity.ok(evaluationService.listCriteria(templateId));
    }

    @PostMapping("/{templateId}/criteria")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Add criterion to an evaluation template (HR/ADMIN only)")
    public ResponseEntity<EvaluationCriterionResponse> addCriterion(
            @PathVariable UUID templateId,
            @Valid @RequestBody EvaluationCriterionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluationService.addCriterion(templateId, request, actor));
    }

    @PutMapping("/criteria/{criterionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Update evaluation criterion (HR/ADMIN only)")
    public ResponseEntity<EvaluationCriterionResponse> updateCriterion(
            @PathVariable UUID criterionId,
            @Valid @RequestBody EvaluationCriterionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(evaluationService.updateCriterion(criterionId, request, actor));
    }
}
