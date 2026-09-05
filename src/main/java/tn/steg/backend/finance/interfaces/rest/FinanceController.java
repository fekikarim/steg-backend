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
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "Open a finance case for a COMPLETED OBLIGATOIRE internship")
    public ResponseEntity<FinanceCaseResponse> openFinanceCase(
            @Valid @RequestBody OpenFinanceCaseRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.openFinanceCase(request.internshipId(), actor));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "List finance cases, optionally filtered by status")
    public ResponseEntity<Page<FinanceCaseResponse>> listFinanceCases(
            @RequestParam(required = false) FinanceCaseStatus status,
            @PageableDefault(size = 20, sort = "openedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(financeService.listFinanceCases(status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "Get a finance case with calculation, dossier and approval history")
    public ResponseEntity<FinanceCaseResponse> getFinanceCase(@PathVariable UUID id) {
        return ResponseEntity.ok(financeService.getFinanceCase(id));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "Attach an uploaded document to the finance dossier")
    public ResponseEntity<FinanceCaseDocumentResponse> attachDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AttachFinanceDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.attachDocument(id, request, actor));
    }

    @PatchMapping("/{id}/documents/{documentId}")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "Review (verify) one dossier document")
    public ResponseEntity<FinanceCaseDocumentResponse> reviewDocument(
            @PathVariable UUID id,
            @PathVariable UUID documentId,
            @Valid @RequestBody ReviewFinanceDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.reviewDocument(id, documentId, request, actor));
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    @Operation(summary = "Recompute the payment snapshot (pre-decision only, audited)")
    public ResponseEntity<FinanceCaseResponse> recalculate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.recalculate(id, actor));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('FINANCE')")
    @Operation(summary = "Approve payment and issue the receipt (FINANCE role only)")
    public ResponseEntity<FinanceCaseResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) PaymentDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.approve(id, request, actor));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('FINANCE')")
    @Operation(summary = "Reject payment with a mandatory reason (FINANCE role only)")
    public ResponseEntity<FinanceCaseResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(financeService.reject(id, request, actor));
    }

    @GetMapping("/{id}/receipt")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download the payment receipt PDF (FINANCE/ADMIN/HR or internship supervisor)")
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
