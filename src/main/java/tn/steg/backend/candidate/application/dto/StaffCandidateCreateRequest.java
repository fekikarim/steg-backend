package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Staff creation of a candidate profile (AGENTS.md §5.1, §6.2, ambiguity A4).
 *
 * <p>The profile is created WITHOUT a front-office login: the person has no
 * account yet, so {@code user_id} stays NULL and the candidate claims the
 * profile later by registering with the same email or CIN (see
 * {@code CandidateService.createCandidate}). The managing staff member is stored
 * in {@code candidates.managed_by_user_id} (V44) so the profile is visible in its
 * creator's scope straight away.
 *
 * <p>Creating a profile never creates an application and never bypasses the
 * Admin-only approval of §5.2.
 */
@Schema(description = "Payload for creating a candidate profile on behalf of somebody (staff)")
public record StaffCandidateCreateRequest(

        @NotNull(message = "Candidate data is required")
        @Valid
        @Schema(description = "Profile fields; nationalId (CIN) is REQUIRED on this endpoint")
        CandidateRequest candidate,

        @Schema(description = "Staff member who manages this candidate. "
                + "ADMIN may choose any supervisor or the admin himself; "
                + "SUPERVISOR may only create candidates managed by himself "
                + "(omitting it means 'myself').",
                example = "3f1c1f0e-0d0a-4a1e-9c2a-9a0b1c2d3e4f")
        UUID managedByUserId
) {}