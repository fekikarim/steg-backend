package tn.steg.backend.ai.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.ai.application.ChatbotService;
import tn.steg.backend.ai.application.dto.ChatbotHistoryResponse;
import tn.steg.backend.ai.application.dto.ChatbotQueryRequest;
import tn.steg.backend.ai.application.dto.ChatbotQueryResponse;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;

/**
 * Back-office chatbot (AGENTS.md §7.5, backend only).
 *
 * <p>Admin (global scope) and Supervisor (own scope): curated knowledge +
 * caller role/identity in the system prompt, live data lookups executed
 * backend-side under the caller's own permissions, per-user server-side
 * history, rate limiting, input/output safety policy and a friendly
 * unavailable state. Advisory text only — never authoritative state.
 */
@RestController
@RequestMapping("/api/ai/chatbot")
@RequiredArgsConstructor
@Tag(name = "AI Chatbot", description = "Role-scoped back-office assistant (Admin/Supervisor)")
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/query")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @RateLimited(name = "ai-chatbot", limit = 20, windowSeconds = 60)
    @Operation(summary = "Ask the back-office assistant (scoped tools, bounded history, audited)")
    public ResponseEntity<ChatbotQueryResponse> query(
            @Valid @RequestBody ChatbotQueryRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(chatbotService.query(actor, request));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Read my own conversation history (bounded, oldest first)")
    public ResponseEntity<ChatbotHistoryResponse> history(
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(chatbotService.history(actor));
    }

    @DeleteMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Clear my own conversation history")
    public ResponseEntity<Void> clearHistory(
            @AuthenticationPrincipal UserPrincipal actor) {
        chatbotService.clearHistory(actor);
        return ResponseEntity.noContent().build();
    }
}
