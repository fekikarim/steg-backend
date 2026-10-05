package tn.steg.backend.certificate.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.certificate.application.CertificateService;
import tn.steg.backend.certificate.application.dto.CertificateDetailResponse;
import tn.steg.backend.certificate.application.dto.CertificateResponse;
import tn.steg.backend.certificate.application.dto.CertificateRowResponse;
import tn.steg.backend.certificate.domain.model.CertificateStatus;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REST adapter for internship certificates (Phase A11).
 *
 * <p>The generation endpoint takes no request body at all: the generation
 * date is captured server-side, so a spoofed client date cannot even be
 * expressed, let alone honored.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Certificates", description = "Endpoints for internship certificate generation and download")
public class CertificateController {

    private final CertificateService certificateService;

    @PostMapping("/api/internships/{id}/certificates")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Generate the internship certificate PDF (COMPLETED + APPROVED validation, ADMIN only)")
    public ResponseEntity<CertificateResponse> generateCertificate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(certificateService.generateCertificate(id, actor));
    }

    @GetMapping("/api/certificates/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a certificate PDF (staff, supervisor or intern owner)")
    public ResponseEntity<InputStreamResource> downloadCertificate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            jakarta.servlet.http.HttpServletRequest request) {
        CertificateService.DownloadStream stream =
                certificateService.downloadCertificate(id, actor, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .body(new InputStreamResource(stream.inputStream()));
    }

    // ------------------------------------------------------------------
    // S8 workspace (AGENTS.md §5.7, Admin only)
    // ------------------------------------------------------------------

    @GetMapping("/api/certificates")
    @PreAuthorize("@authz.hasRole('ADMIN')")
    @Operation(summary = "Certificate workspace list: one server page (Admin only)")
    public ResponseEntity<Page<CertificateRowResponse>> listCertificates(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CertificateStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(certificateService.searchCertificates(q, status, pageable, actor));
    }

    @GetMapping("/api/certificates/{id}/detail")
    @PreAuthorize("@authz.hasRole('ADMIN')")
    @Operation(summary = "Certificate detail with version history (Admin only)")
    public ResponseEntity<CertificateDetailResponse> getCertificate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(certificateService.getCertificate(id, actor));
    }

    public record RegenerateCommand(LocalDate issueDate) {
    }

    @PutMapping("/api/certificates/{id}")
    @PreAuthorize("@authz.hasRole('ADMIN')")
    @Operation(summary = "Edit and regenerate: re-issues the PDF as a new version (Admin only)")
    public ResponseEntity<CertificateDetailResponse> regenerateCertificate(
            @PathVariable UUID id,
            @RequestBody(required = false) RegenerateCommand command,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(certificateService.regenerateCertificate(
                id, command != null ? command.issueDate() : null, actor));
    }

    @DeleteMapping("/api/certificates/{id}")
    @PreAuthorize("@authz.hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a certificate (REVOKED, row kept for audit; Admin only)")
    public ResponseEntity<Void> revokeCertificate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        certificateService.revokeCertificate(id, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/certificates/{id}/versions/{version}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download one version of a certificate PDF (same scope as download)")
    public ResponseEntity<InputStreamResource> downloadCertificateVersion(
            @PathVariable UUID id,
            @PathVariable Integer version,
            @AuthenticationPrincipal UserPrincipal actor,
            jakarta.servlet.http.HttpServletRequest request) {
        CertificateService.DownloadStream stream =
                certificateService.downloadCertificate(id, version, actor, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .body(new InputStreamResource(stream.inputStream()));
    }
}
