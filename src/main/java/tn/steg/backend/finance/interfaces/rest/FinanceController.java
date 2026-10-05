package tn.steg.backend.finance.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.finance.application.FinanceService;
import tn.steg.backend.finance.application.dto.AttachFinanceDocumentRequest;
import tn.steg.backend.finance.application.dto.FinanceCaseDocumentResponse;
import tn.steg.backend.finance.application.dto.FinanceCaseResponse;
import tn.steg.backend.finance.application.dto.OpenFinanceCaseRequest;
import tn.steg.backend.finance.application.dto.PaymentDecisionRequest;
import tn.steg.backend.finance.application.dto.ReviewFinanceDocumentRequest;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;

import java.util.UUID;

/**
 * REST adapter for the finance pipeline (Phase A11): case lifecycle, dossier
 * review, deterministic recalculation, human FINANCE decisions, and receipt
 * download. Amounts are never accepted from clients — only recomputed.
 */
@RestController
@RequestMapping("/api/finance-cases")
@RequiredArgsConstructor
@Tag(name = "Finance", description = "Endpoints for Finance Cases, dossier review, payment decisions and receipts")
public class FinanceController {

    private final FinanceService financeService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Open a finance case for a COMPLETED OBLIGATOIRE internship")
    public ResponseEntity<FinanceCaseResponse> openFinanceCase(
            @Valid @RequestBody OpenFinanceCaseRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.openFinanceCase(request.internshipId(), actor));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "List finance cases, optionally filtered by status (supervisors see assigned cases only)")
    public ResponseEntity<Page<FinanceCaseResponse>> listFinanceCases(
            @RequestParam(required = false) FinanceCaseStatus status,
            @PageableDefault(size = 20, sort = "openedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.listFinanceCases(status, pageable, actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Get a finance case with calculation, dossier and approval history (supervisors: assigned cases only)")
    public ResponseEntity<FinanceCaseResponse> getFinanceCase(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.getFinanceCase(id, actor));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Attach an uploaded document to the finance dossier")
    public ResponseEntity<FinanceCaseDocumentResponse> attachDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AttachFinanceDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.attachDocument(id, request, actor));
    }

    @PatchMapping("/{id}/documents/{documentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Review (verify) one dossier document")
    public ResponseEntity<FinanceCaseDocumentResponse> reviewDocument(
            @PathVariable UUID id,
            @PathVariable UUID documentId,
            @Valid @RequestBody ReviewFinanceDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.reviewDocument(id, documentId, request, actor));
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Recompute the payment snapshot (pre-decision only, audited)")
    public ResponseEntity<FinanceCaseResponse> recalculate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.recalculate(id, actor));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Approve payment and issue the receipt (ADMIN, or assigned supervisor)")
    public ResponseEntity<FinanceCaseResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) PaymentDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.approve(id, request, actor));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Reject payment with a mandatory reason (ADMIN, or assigned supervisor)")
    public ResponseEntity<FinanceCaseResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.reject(id, request, actor));
    }

    @GetMapping("/{id}/receipt")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download the payment receipt PDF (ADMIN or assigned supervisor)")
    public ResponseEntity<InputStreamResource> downloadReceipt(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            jakarta.servlet.http.HttpServletRequest request) {
        FinanceService.DownloadStream stream = financeService.downloadReceipt(id, actor, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .body(new InputStreamResource(stream.inputStream()));
    }
}
