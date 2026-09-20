package tn.steg.backend.document.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.application.dto.DocumentAiValidationResponse;

import java.io.IOException;

/**
 * Anonymous AI validation for the wizard step-4 gate (before account creation).
 * No document is persisted; bytes are validated in-memory via Spring Boot → Python.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Public ad-hoc document validation (anonymous wizard)")
public class PublicDocumentValidationController {

    private final DocumentService documentService;

    @PublicEndpoint
    @PostMapping(value = "/api/public/documents/validation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimited(name = "public-doc-validation", limit = 20, windowSeconds = 60)
    @Operation(summary = "Public AI validation (anonymous wizard gate)",
            description = "Validates a PDF in-memory without storing it: checks document-type keywords "
                    + "(e.g. Demande de Stage / internship application / طلب تدريب) and candidate full name "
                    + "(first+last in either order). Spring Boot is the authority – Python is called server-side only.")
    public ResponseEntity<DocumentAiValidationResponse> validatePublic(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "fullName", required = false) String fullName) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes = file.getBytes();
        var result = documentService.validatePublicDocumentAi(bytes, type, fullName);
        return ResponseEntity.ok(result);
    }
}
