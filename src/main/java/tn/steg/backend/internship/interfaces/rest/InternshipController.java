package tn.steg.backend.internship.interfaces.rest;

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
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.InternshipSummaryService;
import tn.steg.backend.internship.application.SupervisedInternshipService;
import tn.steg.backend.internship.application.dto.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/internships")
@RequiredArgsConstructor
@Tag(name = "Internships", description = "Internship, Assignment & Deterministic Classification Engine")
public class InternshipController {

    private final InternshipService internshipService;
    private final InternshipLifecycleService internshipLifecycleService;
    private final SupervisedInternshipService supervisedInternshipService;
    private final InternshipSummaryService internshipSummaryService;

    // -------------------------------------------------------------------------
    // Querying
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @GetMapping
    @Operation(summary = "List all internships (staff only)")
    public ResponseEntity<List<InternshipResponse>> listInternships(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(internshipService.listInternships(principal));
    }

    // T12/B2: the caller's own supervised internships with server-aggregated
    // counts (D1b/BR-03). Unlike GET /api/internships — global for ADMIN —
    // this is own-scope for every staff caller, ADMIN included. The literal
    // path wins over /{id} (same convention as /manage elsewhere).
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @GetMapping("/supervised")
    @Operation(summary = "List my supervised internships with workload counts (own scope, staff only)")
    public ResponseEntity<List<SupervisedInternshipResponse>> listSupervised(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(supervisedInternshipService.supervisedInternships(principal));
    }

    // T13/B13: one-call student home snapshot (same DTOs and service calls
    // as the lists, so numbers cannot drift). Intern-scoped: another
    // student's summary is 403/404, never leaked.
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#id)")
    @Operation(summary = "Student home summary for one internship (own internship only)")
    @GetMapping("/{id}/summary")
    public ResponseEntity<InternshipSummaryResponse> internshipSummary(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(internshipSummaryService.summary(id, principal));
    }

    // A14 IDOR fix: candidates may read ONLY their own internship. The scope is
    // enforced in the service (404, no oracle); the method-security gate stays
    // open for authenticated users so an out-of-scope read is 404, not 403.
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    @Operation(summary = "Get internship details by ID")
    public ResponseEntity<InternshipResponse> getInternship(@PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(internshipService.getInternship(id, principal));
    }

    // -------------------------------------------------------------------------
    // Creation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/from-application")
    @Operation(summary = "Create an internship from an APPROVED application",
               description = "Copies candidate, proposed subject and dates. Computes deterministic classification.")
    public ResponseEntity<InternshipResponse> createFromApplication(
            @Valid @RequestBody InternshipCreateFromApplicationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(internshipService.createFromApplication(request, principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/manual")
    @Operation(summary = "Manually create an internship for a candidate",
               description = "Computes deterministic classification based on date boundaries.")
    public ResponseEntity<InternshipResponse> createManual(
            @Valid @RequestBody InternshipCreateManualRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(internshipService.createManual(request, principal));
    }

    // -------------------------------------------------------------------------
    // Dates Update & Reclassification
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Explicit lifecycle (AGENTS.md §4)
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{id}/status-transitions")
    @Operation(summary = "Advance an internship along the explicit status model",
               description = "APPROVED → IN_PROGRESS → REPORT_SUBMITTED → UNDER_VALIDATION → VALIDATED "
                       + "→ RECEIPT_ISSUED. Any other pair returns 409. Admin-only; the intern report "
                       + "channel drives the same service internally.")
    public ResponseEntity<InternshipResponse> transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody InternshipStatusTransitionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        // S6b: VALIDATED and RECEIPT_ISSUED are RESERVED for the validation use
        // case (AGENTS.md §5.11). They may only be reached by the Admin's manual
        // per-document decision and by the (idempotent) payment-receipt
        // generation — never by a bare status call, which would let anyone skip
        // the decision. Enforced HERE so every caller of this endpoint is
        // covered, including future ones.
        if (InternshipLifecycleService.RESERVED_FOR_VALIDATION.contains(request.targetStatus())) {
            throw new BusinessRuleException("INTERNSHIP_STATUS_RESERVED",
                    request.targetStatus() + " is only reachable through the internship validation "
                            + "decision and the payment receipt (AGENTS.md §5.11).");
        }
        return ResponseEntity.ok(internshipLifecycleService.transition(
                id, request.targetStatus(), request.comment(), principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PutMapping("/{id}/dates")
    @Operation(summary = "Update internship dates and trigger reclassification",
               description = "Automatically recomputes type and requirement; client cannot dictate them.")
    public ResponseEntity<InternshipResponse> updateDates(
            @PathVariable UUID id,
            @Valid @RequestBody InternshipUpdateDatesRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(internshipService.updateDates(id, request, principal));
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an internship")
    public ResponseEntity<InternshipResponse> cancelInternship(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(internshipService.cancelInternship(id, principal));
    }

    // -------------------------------------------------------------------------
    // Assignments
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{id}/assignments")
    @Operation(summary = "Assign or reassign an internship",
               description = "Atomically ends any current ACTIVE assignment and activates the new one.")
    public ResponseEntity<InternshipAssignmentResponse> assign(
            @PathVariable UUID id,
            @Valid @RequestBody InternshipAssignmentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(internshipService.assign(id, request, principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN') or @authz.isSupervisorOf(#id)")
    @GetMapping("/{id}/assignments")
    @Operation(summary = "List assignment history for an internship")
    public ResponseEntity<List<InternshipAssignmentResponse>> listAssignments(@PathVariable UUID id) {
        return ResponseEntity.ok(internshipService.listAssignments(id));
    }

    // -------------------------------------------------------------------------
    // Transparency / Classification Explanation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasRole('ADMIN') or @authz.isSupervisorOf(#id) or @authz.isInternOf(#id)")
    @GetMapping("/{id}/classification")
    @Operation(summary = "Get computed classification explanation",
               description = "Exposes computed type, requirement, payment eligibility, duration in days, and rule rationale.")
    public ResponseEntity<InternshipClassificationResponse> getClassification(@PathVariable UUID id) {
        return ResponseEntity.ok(internshipService.getClassification(id));
    }
}
