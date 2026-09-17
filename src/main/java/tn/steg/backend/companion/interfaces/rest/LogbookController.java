package tn.steg.backend.companion.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.LogbookService;
import tn.steg.backend.companion.application.dto.LogbookResponse;

import java.util.UUID;

@RestController
@RequestMapping("/api/internships/{internshipId}/logbook")
@RequiredArgsConstructor
@Tag(name = "Logbook", description = "Official internship logbook lifecycle (draft → submitted → validated → official)")
public class LogbookController {

    private final LogbookService logbookService;

    @PostMapping("/submit")
    @PreAuthorize("@authz.isParticipantOf(#internshipId)")
    @Operation(summary = "Intern submits their final logbook for supervisor validation")
    public ResponseEntity<LogbookResponse> submit(
            @PathVariable UUID internshipId,
            @RequestBody SubmitRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(LogbookResponse.from(
                logbookService.submitForValidation(internshipId, request.finalText(), actor)));
    }

    @PostMapping("/{logbookId}/validate")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @Operation(summary = "Supervisor validates the submitted logbook")
    public ResponseEntity<LogbookResponse> validate(
            @PathVariable UUID internshipId,
            @PathVariable UUID logbookId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(LogbookResponse.from(
                logbookService.validate(logbookId, actor)));
    }

    @PostMapping("/{logbookId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR')")
    @Operation(summary = "Supervisor rejects the submitted logbook with a reason")
    public ResponseEntity<LogbookResponse> reject(
            @PathVariable UUID internshipId,
            @PathVariable UUID logbookId,
            @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(LogbookResponse.from(
                logbookService.reject(logbookId, request.reason(), actor)));
    }

    @PostMapping("/{logbookId}/official")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "HR/ADMIN finalizes a VALIDATED logbook as OFFICIAL")
    public ResponseEntity<LogbookResponse> promoteToOfficial(
            @PathVariable UUID internshipId,
            @PathVariable UUID logbookId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(LogbookResponse.from(
                logbookService.promoteToOfficial(logbookId, actor)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'SUPERVISOR') or @authz.isParticipantOf(#internshipId)")
    @Operation(summary = "Get the logbook for an internship (read-only)")
    public ResponseEntity<LogbookResponse> getByInternship(
            @PathVariable UUID internshipId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(LogbookResponse.from(
                logbookService.getByInternship(internshipId)));
    }

    public record SubmitRequest(String finalText) {}
    public record RejectRequest(String reason) {}
}