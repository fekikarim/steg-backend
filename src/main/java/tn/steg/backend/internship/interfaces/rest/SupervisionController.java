package tn.steg.backend.internship.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.application.DocumentPreparationService;
import tn.steg.backend.internship.application.dto.DocumentPreparationRequest;
import tn.steg.backend.internship.application.dto.DocumentPreparationResponse;

@RestController
@RequestMapping("/api/supervision")
@RequiredArgsConstructor
@Tag(name = "Supervision", description = "Supervisor self-service actions on own students (T14)")
public class SupervisionController {

    private final DocumentPreparationService documentPreparationService;

    // T14/D14: a supervisor asks his own students to prepare validation
    // documents. Own-scope only (D1b/BR-03, enforced per internship id —
    // out-of-scope is 404); rate-limited; idempotent via
    // X-Idempotency-Key; audited per covered internship.
    @PostMapping("/document-preparation")
    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'SUPERVISOR')")
    @RateLimited(name = "document-preparation", limit = 10, windowSeconds = 60)
    @Operation(summary = "Notify own students to prepare validation documents",
            description = "Accepts internship ids of the caller's supervised internships only. "
                    + "Sends one DOCUMENTS_PREPARATION_REQUESTED notification per covered internship. "
                    + "Send X-Idempotency-Key to make a double submit replay without duplicating.")
    public ResponseEntity<DocumentPreparationResponse> requestDocumentPreparation(
            @RequestBody DocumentPreparationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(
                documentPreparationService.notifyDocumentsPreparation(actor, request));
    }
}
