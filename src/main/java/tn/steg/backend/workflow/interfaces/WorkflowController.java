package tn.steg.backend.workflow.interfaces;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowActionResponse;
import tn.steg.backend.workflow.application.dto.WorkflowInstanceResponse;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for workflow operations.
 * <p>
 * Application workflow endpoints:
 *   GET  /api/applications/{id}/workflow          → current state
 *   POST /api/applications/{id}/workflow/actions  → execute a transition
 *   GET  /api/workflows/{instanceId}/actions      → audit trail for any instance
 *
 * Internship workflow endpoints:
 *   GET  /api/internships/{id}/workflow           → current state
 *   POST /api/internships/{id}/workflow/actions   → execute a transition
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "State machine for internship application and internship lifecycle transitions")
public class WorkflowController {

    private final WorkflowService workflowService;

    // =========================================================================
    // Application Workflow
    // =========================================================================

    /**
     * Returns the current workflow state (current step, status, timestamps) for an application.
     * Accessible by ADMIN, HR, and the owning CANDIDATE.
     */
    @GetMapping("/api/applications/{id}/workflow")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE')")
    @Operation(summary = "Get application workflow state",
            description = "Returns the current workflow step, status and timestamps for an application "
                    + "(ADMIN, HR or owning CANDIDATE).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workflow instance state"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Application or workflow not found")
    })
    public ResponseEntity<WorkflowInstanceResponse> getApplicationWorkflowState(
            @PathVariable UUID id) {
        return ResponseEntity.ok(workflowService.getApplicationWorkflowState(id));
    }

    /**
     * Executes a workflow transition on an application (e.g. SUBMITTED → UNDER_REVIEW → FINAL_DECISION).
     * Only ADMIN and HR may trigger transitions via this endpoint.
     */
    @PostMapping("/api/applications/{id}/workflow/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Execute an application workflow transition",
            description = "Advances the application state machine (SUBMITTED → UNDER_REVIEW → FINAL_DECISION...). "
                    + "The transition is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transition executed"),
            @ApiResponse(responseCode = "400", description = "Invalid transition for current state"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN or HR"),
            @ApiResponse(responseCode = "404", description = "Application not found"),
            @ApiResponse(responseCode = "422", description = "Business rule violation")
    })
    public ResponseEntity<WorkflowActionResponse> transitionApplication(
            @PathVariable UUID id,
            @RequestBody WorkflowTransitionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        WorkflowActionResponse response = workflowService.transitionApplication(id, request, actor);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Internship Workflow
    // =========================================================================

    /**
     * Returns the current workflow state for an internship.
     */
    @GetMapping("/api/internships/{id}/workflow")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE')")
    @Operation(summary = "Get internship workflow state",
            description = "Returns the current workflow step, status and timestamps for an internship "
                    + "(ADMIN, HR, or the owning CANDIDATE).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workflow instance state"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Internship or workflow not found")
    })
    public ResponseEntity<WorkflowInstanceResponse> getInternshipWorkflowState(
            @PathVariable UUID id) {
        return ResponseEntity.ok(workflowService.getInternshipWorkflowState(id));
    }

    /**
     * Executes a workflow transition on an internship (e.g. PLANNED → ACTIVE → COMPLETED).
     * Only ADMIN and HR may perform transitions.
     */
    @PostMapping("/api/internships/{id}/workflow/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Execute an internship workflow transition",
            description = "Advances the internship state machine (PLANNED → ACTIVE → COMPLETED...). "
                    + "The transition is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transition executed"),
            @ApiResponse(responseCode = "400", description = "Invalid transition for current state"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN or HR"),
            @ApiResponse(responseCode = "404", description = "Internship not found"),
            @ApiResponse(responseCode = "422", description = "Business rule violation")
    })
    public ResponseEntity<WorkflowActionResponse> transitionInternship(
            @PathVariable UUID id,
            @RequestBody WorkflowTransitionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        WorkflowActionResponse response = workflowService.transitionInternship(id, request, actor);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Audit Trail
    // =========================================================================

    /**
     * Returns the full ordered audit trail (all WorkflowActions) for any workflow instance.
     * Accessible by ADMIN and HR only.
     */
    @GetMapping("/api/workflows/{instanceId}/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "List the ordered audit trail of a workflow instance",
            description = "All WorkflowActions ever executed for the instance, oldest first (ADMIN/HR).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ordered action history"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN or HR"),
            @ApiResponse(responseCode = "404", description = "Workflow instance not found")
    })
    public ResponseEntity<List<WorkflowActionResponse>> listWorkflowActions(
            @PathVariable UUID instanceId) {
        return ResponseEntity.ok(workflowService.listActions(instanceId));
    }
}
