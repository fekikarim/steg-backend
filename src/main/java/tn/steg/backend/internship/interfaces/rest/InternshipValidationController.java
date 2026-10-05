package tn.steg.backend.internship.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.finance.application.dto.ValidationReceiptView;
import tn.steg.backend.internship.application.InternshipValidationService;
import tn.steg.backend.internship.application.dto.ValidationDetailResponse;
import tn.steg.backend.internship.application.dto.ValidationQueueQuery;
import tn.steg.backend.internship.application.dto.ValidationQueueRow;
import tn.steg.backend.internship.application.dto.VerificationResultResponse;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;

import java.util.List;
import java.util.UUID;

/**
 * S7 internship validation (AGENTS.md §5.11) — Admin only, end to end.
 * AI verification is advisory (persisted runs, never moves a status); the
 * mandatory manual per-document decision is the only path to VALIDATED; the
 * payment receipt is idempotent and moves the internship to RECEIPT_ISSUED
 * through the single authority.
 */
@RestController
@RequestMapping("/api/internship-validation")
@RequiredArgsConstructor
@Tag(name = "Internship Validation", description = "Final validation of finished internships and payment receipt issuance")
public class InternshipValidationController {

    private final InternshipValidationService validationService;

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @GetMapping("/queue")
    @Operation(summary = "Validation queue: internships from REPORT_SUBMITTED onward, paged and filtered")
    public ResponseEntity<Page<ValidationQueueRow>> queue(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) UUID supervisorUserId,
            @RequestParam(required = false) UUID universityId,
            @RequestParam(required = false) InternshipType type,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(validationService.searchQueue(
                new ValidationQueueQuery(
                        pageable.getPageNumber(), pageable.getPageSize(),
                        pageable.getSort().stream()
                                .map(o -> o.getProperty() + ","
                                        + (o.isAscending() ? "asc" : "desc"))
                                .findFirst().orElse(null),
                        q, statuses, supervisorUserId, universityId, type),
                principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @GetMapping("/{internshipId}")
    @Operation(summary = "Validation detail: candidate, tasks, documents, AI runs, decisions, receipt")
    public ResponseEntity<ValidationDetailResponse> detail(
            @PathVariable UUID internshipId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(validationService.getDetail(internshipId, principal));
    }

    public record VerifyCommand(ValidationDocumentType documentType) {
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{internshipId}/verify")
    @Operation(summary = "Run advisory AI verification on one document (never moves a status)")
    public ResponseEntity<VerificationResultResponse> verify(
            @PathVariable UUID internshipId,
            @RequestBody VerifyCommand command,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(validationService.runVerification(
                internshipId, command.documentType(), principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{internshipId}/decisions")
    @Operation(summary = "Record the mandatory manual decision on one document (VALIDATED/REJECTED + comment on REJECTED)")
    public ResponseEntity<ValidationDetailResponse> decide(
            @PathVariable UUID internshipId,
            @RequestBody ValidationDetailResponse.DecideCommand command,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(validationService.recordDecision(internshipId, command, principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/{internshipId}/receipt")
    @Operation(summary = "Generate the payment receipt PDF (idempotent; moves to RECEIPT_ISSUED)")
    public ResponseEntity<ValidationReceiptView> receipt(
            @PathVariable UUID internshipId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(validationService.generateReceipt(internshipId, principal));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @GetMapping("/{internshipId}/documents/{documentType}/download")
    @Operation(summary = "Download a validation document (report or journal PDF)")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable UUID internshipId,
            @PathVariable ValidationDocumentType documentType,
            @AuthenticationPrincipal UserPrincipal principal) {
        InternshipValidationService.DownloadStream stream =
                validationService.downloadDocument(internshipId, documentType, principal, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + stream.fileName() + "\"")
                .contentType(MediaType.parseMediaType(
                        stream.mimeType() != null ? stream.mimeType() : "application/pdf"))
                .contentLength(stream.size())
                .body(new InputStreamResource(stream.inputStream()));
    }
}
