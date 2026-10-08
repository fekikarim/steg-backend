package tn.steg.backend.companion.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.JournalDocumentService;
import tn.steg.backend.companion.application.dto.JournalEligibilityResponse;
import tn.steg.backend.companion.application.dto.JournalGenerationRequest;
import tn.steg.backend.companion.application.dto.JournalGenerationResponse;

import java.util.UUID;

/**
 * T09 — internship journal window (B5) and journal document generation (B6).
 *
 * <p>Method security is the first half of every rule and the service re-checks
 * the row scope afterwards: a non-participant is refused FIRST with the same
 * 403 for an existing-but-foreign and an unknown id (no existence leak), while
 * an Admin passes method security and then gets the service's 404 for an
 * unknown or out-of-scope row. Eligibility is readable by a participant (the
 * owning intern or the internship's supervisor) plus Admin, while GENERATION is
 * the student's own action — {@code isInternOf} plus the Admin override,
 * exactly like the journal entry creation endpoints of
 * {@code CompanionController}.
 *
 * <p>The server owns the window (BR-20/BR-21: {@code Africa/Tunis}, never the
 * phone clock), the task ratio and the PDF content; the client can only choose
 * the source (its own tasks or a bounded description). AI calls are rate
 * limited per user on one shared bucket and audited as metadata only.
 */
@RestController
@RequestMapping("/api/internships")
@RequiredArgsConstructor
@Tag(name = "Journal Document", description = "T09 journal eligibility window and AI journal PDF generation")
public class JournalDocumentController {

    private final JournalDocumentService journalDocumentService;

    // -------------------------------------------------------------------------
    // B5 — eligibility window
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/journal-eligibility")
    @PreAuthorize("hasRole('ADMIN') or @authz.isParticipantOf(#id)")
    @Operation(summary = "Server-computed journal eligibility window + task completion facts (participant)")
    public ResponseEntity<JournalEligibilityResponse> eligibility(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(journalDocumentService.eligibility(actor, id));
    }

    // -------------------------------------------------------------------------
    // B6 — generation
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/journal/generate")
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#id)")
    @RateLimited(name = "ai-journal-generate", limit = 10, windowSeconds = 60)
    @Operation(summary = "Generate the internship journal PDF from my tasks (owning intern)")
    public ResponseEntity<JournalGenerationResponse> generateFromTasks(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(journalDocumentService.generateFromTasks(actor, id));
    }

    @PostMapping("/{id}/journal/generate-from-text")
    @PreAuthorize("hasRole('ADMIN') or @authz.isInternOf(#id)")
    @RateLimited(name = "ai-journal-generate", limit = 10, windowSeconds = 60)
    @Operation(summary = "Generate the internship journal PDF from my description (owning intern)")
    public ResponseEntity<JournalGenerationResponse> generateFromText(
            @PathVariable UUID id,
            @RequestBody(required = false) JournalGenerationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(journalDocumentService.generateFromText(
                        actor, id, request != null ? request.text() : null));
    }
}
