package tn.steg.backend.ai.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.ai.application.AiService;
import tn.steg.backend.ai.application.dto.AiAnalysisResultResponse;
import tn.steg.backend.ai.application.dto.AiRecommendationResponse;
import tn.steg.backend.ai.application.dto.AiRecommendationReviewRequest;
import tn.steg.backend.ai.application.dto.CandidateAssistantQueryRequest;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistance", description = "Endpoints for advisory AI analysis, candidate Q&A, and recommendation review")
public class AiController {

    private final AiService aiService;

    @PostMapping("/applications/{id}/analyze")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Analyze application documents (Advisory, staff-triggered)")
    public ResponseEntity<AiAnalysisResultResponse> analyzeApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(aiService.analyzeApplication(id, actor));
    }

    @PostMapping("/finance-cases/{id}/analyze")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    @Operation(summary = "Analyze finance case dossier (Advisory, FINANCE-role only)")
    public ResponseEntity<AiAnalysisResultResponse> analyzeFinanceCase(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(aiService.analyzeFinanceCase(id, actor));
    }

    @PostMapping("/internships/{id}/logbook/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isParticipantOf(#id)")
    @Operation(summary = "Generate draft logbook based on journal, tasks, and deliverables (Intern/Supervisor)")
    public ResponseEntity<AiAnalysisResultResponse> generateLogbook(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(aiService.generateLogbookDraft(id, actor));
    }

    @PostMapping("/assistant/query")
    @PreAuthorize("hasRole('CANDIDATE')")
    @Operation(summary = "Candidate virtual assistant Q&A (Strictly candidate-scoped)")
    public ResponseEntity<AiAnalysisResultResponse> queryAssistant(
            @Valid @RequestBody CandidateAssistantQueryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(aiService.queryCandidateAssistant(request, actor));
    }

    @PostMapping("/recommendations/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'FINANCE', 'SUPERVISOR')")
    @Operation(summary = "Review AI recommendation (Human marks ACCEPTED_BY_HUMAN or DISMISSED for traceability)")
    public ResponseEntity<AiRecommendationResponse> reviewRecommendation(
            @PathVariable UUID id,
            @Valid @RequestBody AiRecommendationReviewRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(aiService.reviewRecommendation(id, request, actor));
    }
}
