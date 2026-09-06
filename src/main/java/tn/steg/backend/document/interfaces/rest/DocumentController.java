package tn.steg.backend.document.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.application.dto.*;
import tn.steg.backend.document.domain.model.DocumentType;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Upload, download, and metadata of documents; CV/supporting files attached to applications and internships")
public class DocumentController {

    private final DocumentService documentService;

    // -------------------------------------------------------------------------
    // Document Upload & Metadata
    // -------------------------------------------------------------------------

    @PostMapping(value = "/api/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @RateLimited(name = "document-upload", limit = 10, windowSeconds = 60)
    @Operation(summary = "Upload a document",
            description = "Stores the uploaded file and returns metadata (reference, mime type, size, restrictedAccess). "
                    + "Rate-limited to 10 uploads/minute per user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document created"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid file or document type"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "429", description = "Upload rate limit exceeded (10/min)")
    })
    public ResponseEntity<DocumentResponse> uploadDocument(
            @Parameter(description = "File to upload (PDF, DOCX, PNG...)") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Document category") @RequestParam("type") DocumentType type,
            @AuthenticationPrincipal UserPrincipal actor) {
        DocumentResponse response = documentService.uploadDocument(file, type, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/documents/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get document metadata (no content body)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document metadata"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not the owner nor privileged"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<DocumentResponse> getDocumentMetadata(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentMetadata(id, actor));
    }

    @GetMapping("/api/documents/{id}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download document content",
            description = "Streams the file bytes. Access follows document visibility rules.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File stream"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized to view content"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
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
    @Operation(summary = "Download restricted (CIN-bearing) document content",
            description = "Sensitive documents (national ID, certificates) may only be downloaded by "
                    + "holders of DOCUMENT_VIEW_RESTRICTED or ADMIN, and only from an allowed IP.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File stream"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Missing DOCUMENT_VIEW_RESTRICTED/ADMIN or disallowed IP"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
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
    @Operation(summary = "List documents attached to an application")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attached application documents"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Application not found")
    })
    public ResponseEntity<List<ApplicationDocumentResponse>> getApplicationDocuments(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentsForApplication(id, actor));
    }

    @PostMapping("/api/applications/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'CANDIDATE')")
    @Operation(summary = "Attach an existing document to an application")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document attached"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Application or document not found"),
            @ApiResponse(responseCode = "409", description = "Document already attached")
    })
    public ResponseEntity<ApplicationDocumentResponse> attachApplicationDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AttachDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.attachDocumentToApplication(id, request.documentId(), request.mandatory(), actor));
    }

    @PutMapping("/api/applications/{id}/documents/{documentId}/verify")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Verify (accept/reject) an application document")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid verification state"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN or HR"),
            @ApiResponse(responseCode = "404", description = "Application document not found")
    })
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
    @Operation(summary = "List documents attached to an internship")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attached internship documents"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Internship not found")
    })
    public ResponseEntity<List<InternshipDocumentResponse>> getInternshipDocuments(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(documentService.getDocumentsForInternship(id, actor));
    }

    @PostMapping("/api/internships/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Attach an existing document to an internship")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document attached"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN or HR"),
            @ApiResponse(responseCode = "404", description = "Internship or document not found"),
            @ApiResponse(responseCode = "409", description = "Document already attached")
    })
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
