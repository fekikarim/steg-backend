package tn.steg.backend.candidate.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.candidate.application.CandidateService;
import tn.steg.backend.candidate.application.CandidateWorkspaceService;
import tn.steg.backend.candidate.application.dto.CandidateAccountFilter;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateOverviewResponse;
import tn.steg.backend.candidate.application.dto.CandidateQueueResponse;
import tn.steg.backend.candidate.application.dto.CandidateRequest;
import tn.steg.backend.candidate.application.dto.CandidateSummaryResponse;
import tn.steg.backend.candidate.application.dto.StaffCandidateCreateRequest;
import tn.steg.backend.candidate.application.dto.UniversityResponse;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Candidates", description = "Candidate profile management")
public class CandidateController {

    private final CandidateService candidateService;
    private final CandidateWorkspaceService candidateWorkspaceService;

    /**
     * Self-registration of a candidate profile.
     * Only users with CANDIDATE role may call this.
     */
    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PostMapping("/candidates")
    @Operation(summary = "Create candidate profile (self-registration)",
               description = "Authenticated CANDIDATE users create their own profile exactly once.")
    public ResponseEntity<CandidateDetailResponse> createCandidate(
            @Valid @RequestBody CandidateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(candidateService.createCandidate(request, principal));
    }

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @GetMapping("/candidates/me")
    @Operation(summary = "Get candidate self profile",
               description = "Returns full profile of the authenticated candidate including their nationalId.")
    public ResponseEntity<CandidateDetailResponse> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.getMyProfile(principal));
    }

    @PreAuthorize("@authz.hasRole('CANDIDATE')")
    @PutMapping("/candidates/me")
    @Operation(summary = "Update candidate self profile",
               description = "Allows the authenticated candidate to update their own profile.")
    public ResponseEntity<CandidateDetailResponse> updateMyProfile(
            @Valid @RequestBody CandidateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.updateMyProfile(request, principal));
    }

    /**
     * List all candidates — nationalId is intentionally omitted in the response.
     */
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @GetMapping("/candidates")
    @Operation(summary = "List candidates (staff only)",
               description = "Admin sees all candidates; Supervisor sees only own assigned candidates. nationalId never included.")
    public ResponseEntity<List<CandidateSummaryResponse>> listCandidates(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.listCandidates(principal));
    }

    /**
     * Staff candidate queue — declared BEFORE /{id} so the literal segment always wins.
     */
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @GetMapping("/candidates/manage")
    @Operation(summary = "Staff candidate queue (AGENTS.md §5.1 / §6.2)",
               description = "Server-side pagination, search, filters and sort. ADMIN sees every candidate; "
                       + "SUPERVISOR sees only his own candidates. Scope is resolved server-side and never "
                       + "accepted from the client; soft-deleted profiles are excluded.")
    public ResponseEntity<Page<CandidateQueueResponse>> manageCandidates(
            @RequestParam(required = false, name = "q") String search,
            @RequestParam(required = false) String university,
            @RequestParam(required = false) InternshipType type,
            @RequestParam(required = false) UUID supervisor,
            @RequestParam(required = false) CandidateAccountFilter validation,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateWorkspaceService.search(
                principal, search, university, type, supervisor, validation, status, from, to, pageable));
    }

    /**
     * Staff creation of a candidate profile (AGENTS.md §5.1 Admin / §6.2
     * Supervisor-in-his-own-scope, ambiguity A4).
     *
     * <p>Distinct from {@code POST /api/candidates}, which stays the CANDIDATE-only
     * self-registration endpoint. The created profile has no account: the person
     * claims it by registering on the front office with the same CIN or email.
     * Approval of an application remains Admin-only — creating a profile never
     * creates or approves an application.
     */
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @PostMapping("/candidates/manage")
    @Operation(summary = "Create a candidate profile on behalf of somebody (staff)",
               description = "ADMIN may choose the managing supervisor (or himself); a SUPERVISOR may only "
                       + "create candidates he manages himself. A national ID (CIN) is required. The profile "
                       + "carries no account and the owner claims it later by registering with the same CIN "
                       + "or email.")
    public ResponseEntity<CandidateDetailResponse> createCandidateByStaff(
            @Valid @RequestBody StaffCandidateCreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(candidateService.createCandidateByStaff(request, principal));
    }

    /**
     * Candidate detail — includes nationalId only for the candidate themselves or ADMIN.
     */
    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'SUPERVISOR')")
    @GetMapping("/candidates/{id}")
    @Operation(summary = "Get candidate by ID",
               description = "Full profile including nationalId. Accessible to the candidate themselves or staff.")
    public ResponseEntity<CandidateDetailResponse> getCandidate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.getCandidate(id, principal));
    }

    /**
     * One-call detail aggregate (profile, supervisor, applications, internship,
     * task summary, documents, certificate/receipt) — §5.1 requires a single
     * backend call instead of client-side fan-out.
     */
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @GetMapping("/candidates/{id}/overview")
    @Operation(summary = "Candidate detail aggregate",
               description = "Profile + supervisor + applications + internship + tasks summary + documents + "
                       + "certificate/receipt status in ONE response. Supervisor scope enforced server-side "
                       + "(foreign candidate → 404); CIN only for ADMIN.")
    public ResponseEntity<CandidateOverviewResponse> getCandidateOverview(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateWorkspaceService.overview(principal, id));
    }

    /**
     * Soft delete (V41). Refused with 409 CANDIDATE_HAS_DEPENDENCIES while the
     * candidate still has applications, internships, tasks or receipts.
     */
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @DeleteMapping("/candidates/{id}")
    @Operation(summary = "Soft-delete a candidate profile",
               description = "Marks the profile deleted (kept row, unique CIN). Blocked with 409 when "
                       + "applications/internships/tasks/receipts still reference the candidate.")
    public ResponseEntity<Void> deleteCandidate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        candidateWorkspaceService.delete(principal, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update candidate profile — own profile or staff.
     */
    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'SUPERVISOR')")
    @PutMapping("/candidates/{id}")
    @Operation(summary = "Update candidate profile",
               description = "The candidate can update their own profile; ADMIN may update any.")
    public ResponseEntity<CandidateDetailResponse> updateCandidate(
            @PathVariable UUID id,
            @Valid @RequestBody CandidateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.updateCandidate(id, request, principal));
    }

    /**
     * Public university list used by the front-end registration form.
     */
    @PublicEndpoint
    @SecurityRequirements
    @GetMapping("/universities")
    @Operation(summary = "List active universities (public reference data)")
    public ResponseEntity<List<UniversityResponse>> listUniversities() {
        return ResponseEntity.ok(candidateService.listUniversities());
    }
}
