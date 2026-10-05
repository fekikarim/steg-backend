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
import tn.steg.backend.workflow.application.ApplicationApprovalService;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalResponse;

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
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "State machine for internship application and internship lifecycle transitions")
public class WorkflowController {

    private final WorkflowService workflowService;
        private final ApplicationApprovalService applicationApprovalService;

    // =========================================================================
    // Application Workflow
    // =========================================================================

        @PostMapping("/api/applications/{id}/approve")
        @PreAuthorize("hasRole('ADMIN')")
        @Operation(summary = "Approve an application and assign its supervisor atomically")
        public ResponseEntity<ApplicationApprovalResponse> approveApplication(
                        @PathVariable UUID id,
                        @jakarta.validation.Valid @RequestBody ApplicationApprovalRequest request,
                        @AuthenticationPrincipal UserPrincipal actor) {
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(applicationApprovalService.approve(id, request, actor));
        }

    /**
     * Returns the current workflow state (current step, status, timestamps) for an application.
     * Accessible by ADMIN and the owning CANDIDATE.
     */
    @GetMapping("/api/applications/{id}/workflow")
    @PreAuthorize("hasAnyRole('ADMIN', 'CANDIDATE')")
    @Operation(summary = "Get application workflow state",
            description = "Returns the current workflow step, status and timestamps for an application "
                    + "(ADMIN or owning CANDIDATE).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workflow instance state"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Application or workflow not found")
    })
    public ResponseEntity<WorkflowInstanceResponse> getApplicationWorkflowState(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(workflowService.getApplicationWorkflowState(id, actor));
    }

    /**
     * Executes a workflow transition on an application (e.g. SUBMITTED → UNDER_REVIEW → FINAL_DECISION).
     * Only ADMIN may trigger transitions via this endpoint.
     */
    @PostMapping("/api/applications/{id}/workflow/actions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Execute an application workflow transition",
            description = "Advances the application state machine (SUBMITTED → UNDER_REVIEW → FINAL_DECISION...). "
                    + "The transition is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transition executed"),
            @ApiResponse(responseCode = "400", description = "Invalid transition for current state"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
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
    // Audit Trail
    // =========================================================================

    /**
     * Returns the full ordered audit trail (all WorkflowActions) for any workflow instance.
     * Accessible by ADMIN and HR only.
     */
    @GetMapping("/api/workflows/{instanceId}/actions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List the ordered audit trail of a workflow instance",
            description = "All WorkflowActions ever executed for the instance, oldest first (ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ordered action history"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
            @ApiResponse(responseCode = "404", description = "Workflow instance not found")
    })
    public ResponseEntity<List<WorkflowActionResponse>> listWorkflowActions(
            @PathVariable UUID instanceId) {
        return ResponseEntity.ok(workflowService.listActions(instanceId));
    }
}
