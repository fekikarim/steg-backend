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
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/internships")
@RequiredArgsConstructor
@Tag(name = "Internships", description = "Internship, Assignment & Deterministic Classification Engine")
public class InternshipController {

    private final InternshipService internshipService;

    // -------------------------------------------------------------------------
    // Querying
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @GetMapping
    @Operation(summary = "List all internships (staff only)")
    public ResponseEntity<List<InternshipResponse>> listInternships() {
        return ResponseEntity.ok(internshipService.listInternships());
    }

    // A14 IDOR fix: candidates may read ONLY their own internship. Staff keep
    // wide read (consistent with the staff-only list endpoint); cross-candidate
    // reads previously passed the role-only gate and leaked candidateFullName.
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR', 'SUPERVISOR') or @authz.isInternOf(#id)")
    @GetMapping("/{id}")
    @Operation(summary = "Get internship details by ID")
    public ResponseEntity<InternshipResponse> getInternship(@PathVariable UUID id) {
        return ResponseEntity.ok(internshipService.getInternship(id));
    }

    // -------------------------------------------------------------------------
    // Creation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @PostMapping("/from-application")
    @Operation(summary = "Create an internship from an ACCEPTED application",
               description = "Copies candidate, proposed subject and dates. Computes deterministic classification.")
    public ResponseEntity<InternshipResponse> createFromApplication(
            @Valid @RequestBody InternshipCreateFromApplicationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(internshipService.createFromApplication(request, principal));
    }

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
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

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
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

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
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

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
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

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @GetMapping("/{id}/assignments")
    @Operation(summary = "List assignment history for an internship")
    public ResponseEntity<List<InternshipAssignmentResponse>> listAssignments(@PathVariable UUID id) {
        return ResponseEntity.ok(internshipService.listAssignments(id));
    }

    // -------------------------------------------------------------------------
    // Transparency / Classification Explanation
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR', 'SUPERVISOR') or @authz.isInternOf(#id)")
    @GetMapping("/{id}/classification")
    @Operation(summary = "Get computed classification explanation",
               description = "Exposes computed type, requirement, payment eligibility, duration in days, and rule rationale.")
    public ResponseEntity<InternshipClassificationResponse> getClassification(@PathVariable UUID id) {
        return ResponseEntity.ok(internshipService.getClassification(id));
    }
}
