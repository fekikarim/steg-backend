package tn.steg.backend.certificate.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.certificate.application.CertificateService;
import tn.steg.backend.certificate.application.dto.CertificateResponse;
import tn.steg.backend.common.domain.model.UserPrincipal;

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
    @PreAuthorize("hasAnyRole('ADMIN', 'HR') or @authz.isSupervisorOf(#id)")
    @Operation(summary = "Generate the internship certificate PDF (COMPLETED internships, active supervisor)")
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
}
