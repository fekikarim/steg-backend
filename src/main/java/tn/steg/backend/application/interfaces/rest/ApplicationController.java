package tn.steg.backend.application.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.application.application.ApplicationService;
import tn.steg.backend.application.application.dto.ApplicationCreateRequest;
import tn.steg.backend.application.application.dto.ApplicationResponse;
import tn.steg.backend.application.application.dto.ApplicationUpdateRequest;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Tag(name = "Applications", description = "Internship application lifecycle management")
public class ApplicationController {

    private final ApplicationService applicationService;

    // -------------------------------------------------------------------------
    // Creation & listing
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PostMapping
    @Operation(summary = "Create a new application in DRAFT",
               description = "Authenticated CANDIDATE creates a draft application. Reference is server-generated.")
    public ResponseEntity<ApplicationResponse> createApplication(
            @Valid @RequestBody ApplicationCreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(applicationService.createApplication(request, principal));
    }

    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'HR')")
    @GetMapping
    @Operation(summary = "List applications",
               description = "CANDIDATE sees their own; ADMIN/HR see all.")
    public ResponseEntity<List<ApplicationResponse>> listApplications(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.listApplications(principal));
    }

    // -------------------------------------------------------------------------
    // Detail & mutation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'HR')")
    @GetMapping("/{id}")
    @Operation(summary = "Get application by ID",
               description = "CANDIDATE can only access their own; ADMIN/HR can access any.")
    public ResponseEntity<ApplicationResponse> getApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.getApplication(id, principal));
    }

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PutMapping("/{id}")
    @Operation(summary = "Update DRAFT application fields",
               description = "Only allowed while status = DRAFT. Candidate must own the application.")
    public ResponseEntity<ApplicationResponse> updateApplication(
            @PathVariable UUID id,
            @Valid @RequestBody ApplicationUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.updateApplication(id, request, principal));
    }

    // -------------------------------------------------------------------------
    // State transitions — candidate
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit application (DRAFT → SUBMITTED)",
               description = "Candidate finalises and submits their draft. Submission date is set server-side.")
    public ResponseEntity<ApplicationResponse> submitApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.submitApplication(id, principal));
    }

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PostMapping("/{id}/withdraw")
    @Operation(summary = "Withdraw application",
               description = "Candidate withdraws their application. Not allowed once ACCEPTED.")
    public ResponseEntity<ApplicationResponse> withdrawApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.withdrawApplication(id, principal));
    }

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PostMapping("/{id}/resubmit")
    @Operation(summary = "Resubmit after corrections (NEEDS_CORRECTION → SUBMITTED)",
               description = "Candidate corrects their application and resubmits.")
    public ResponseEntity<ApplicationResponse> resubmitApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(applicationService.resubmitApplication(id, principal));
    }

    // -------------------------------------------------------------------------
    // State transitions — staff
    // -------------------------------------------------------------------------
    //
    // Phase A5: Direct status mutation via this controller has been superseded by
    // the Workflow Engine. Staff must use:
    //   POST /api/applications/{id}/workflow/actions
    // with a WorkflowTransitionRequest body.
    //
    // Steps:
    //   SUBMITTED  → UNDER_REVIEW   : actionType=VALIDATION, targetStepCode=UNDER_REVIEW
    //   UNDER_REVIEW → FINAL_DECISION : actionType=APPROVAL, targetStepCode=FINAL_DECISION,
    //                                   decision=APPROVED|REJECTED|NEEDS_CORRECTION, comment=...
}
