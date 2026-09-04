package tn.steg.backend.candidate.interfaces.rest;

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
import tn.steg.backend.candidate.application.CandidateService;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateRequest;
import tn.steg.backend.candidate.application.dto.CandidateSummaryResponse;
import tn.steg.backend.candidate.application.dto.UniversityResponse;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Candidates", description = "Candidate profile management")
public class CandidateController {

    private final CandidateService candidateService;

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
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @GetMapping("/candidates")
    @Operation(summary = "List all candidates (staff only)",
               description = "Returns summary view — nationalId (CIN) is never included.")
    public ResponseEntity<List<CandidateSummaryResponse>> listCandidates() {
        return ResponseEntity.ok(candidateService.listCandidates());
    }

    /**
     * Candidate detail — includes nationalId only for the candidate themselves or ADMIN/HR.
     */
    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'HR')")
    @GetMapping("/candidates/{id}")
    @Operation(summary = "Get candidate by ID",
               description = "Full profile including nationalId. Accessible to the candidate themselves or staff.")
    public ResponseEntity<CandidateDetailResponse> getCandidate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(candidateService.getCandidate(id, principal));
    }

    /**
     * Update candidate profile — own profile or staff.
     */
    @PreAuthorize("@authz.hasAnyRole('CANDIDATE', 'ADMIN', 'HR')")
    @PutMapping("/candidates/{id}")
    @Operation(summary = "Update candidate profile",
               description = "The candidate can update their own profile; ADMIN/HR may update any.")
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
    @GetMapping("/universities")
    @Operation(summary = "List active universities (public reference data)")
    public ResponseEntity<List<UniversityResponse>> listUniversities() {
        return ResponseEntity.ok(candidateService.listUniversities());
    }
}
