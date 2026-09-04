package tn.steg.backend.document.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.application.dto.*;
import tn.steg.backend.document.domain.model.DocumentType;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    // -------------------------------------------------------------------------
    // Document Upload & Metadata
    // -------------------------------------------------------------------------

    @PostMapping(value = "/api/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") DocumentType type,
            @AuthenticationPrincipal UserPrincipal actor) {
        DocumentResponse response = documentService.uploadDocument(file, type, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/documents/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DocumentResponse> getDocumentMetadata(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentMetadata(id, actor));
    }

    @GetMapping("/api/documents/{id}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        DocumentService.DownloadStream stream = documentService.getDownloadStream(id, actor);
        InputStreamResource resource = new InputStreamResource(stream.inputStream());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .body(resource);
    }

    @GetMapping("/api/documents/{id}/download-restricted")
    @PreAuthorize("hasAuthority('DOCUMENT_VIEW_RESTRICTED') or hasRole('ADMIN')")
    public ResponseEntity<Resource> downloadRestrictedDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            HttpServletRequest request) {
        String ipAddress = extractClientIp(request);
        DocumentService.DownloadStream stream = documentService.getRestrictedDownloadStream(id, actor, ipAddress);
        InputStreamResource resource = new InputStreamResource(stream.inputStream());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stream.fileName() + "\"")
                .contentType(MediaType.parseMediaType(stream.mimeType()))
                .contentLength(stream.size())
                .body(resource);
    }

    // -------------------------------------------------------------------------
    // Application Documents
    // -------------------------------------------------------------------------

    @GetMapping("/api/applications/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE')")
    public ResponseEntity<List<ApplicationDocumentResponse>> getApplicationDocuments(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentsForApplication(id, actor));
    }

    @PostMapping("/api/applications/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE')")
    public ResponseEntity<ApplicationDocumentResponse> attachApplicationDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AttachDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.attachDocumentToApplication(id, request.documentId(), request.mandatory(), actor));
    }

    @PutMapping("/api/applications/{id}/documents/{documentId}/verify")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<ApplicationDocumentResponse> verifyApplicationDocument(
            @PathVariable UUID id,
            @PathVariable UUID documentId,
            @Valid @RequestBody DocumentVerificationRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.verifyApplicationDocument(id, documentId, request, actor));
    }

    // -------------------------------------------------------------------------
    // Internship Documents
    // -------------------------------------------------------------------------

    @GetMapping("/api/internships/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE', 'SUPERVISOR')")
    public ResponseEntity<List<InternshipDocumentResponse>> getInternshipDocuments(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentsForInternship(id, actor));
    }

    @PostMapping("/api/internships/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<InternshipDocumentResponse> attachInternshipDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AttachDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.attachDocumentToInternship(id, request.documentId(), request.mandatory(), actor));
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
