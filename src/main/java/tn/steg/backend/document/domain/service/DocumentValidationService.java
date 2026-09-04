package tn.steg.backend.document.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.document.domain.model.DocumentType;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Validates document files: size limits per DocumentType, Tika content inspection,
 * spoofing detection, and SHA-256 checksum calculation.
 */
@Slf4j
@Service
public class DocumentValidationService {

    private final Tika tika = new Tika();

    // Max size in bytes per DocumentType
    private static final Map<DocumentType, Long> SIZE_LIMITS = Map.of(
            DocumentType.CIN_COPY, 5L * 1024 * 1024,                  // 5 MB
            DocumentType.CV, 5L * 1024 * 1024,                        // 5 MB
            DocumentType.MOTIVATION_LETTER, 5L * 1024 * 1024,         // 5 MB
            DocumentType.TRANSCRIPT, 10L * 1024 * 1024,               // 10 MB
            DocumentType.UNIVERSITY_CONVENTION, 10L * 1024 * 1024,    // 10 MB
            DocumentType.STEG_INTERNSHIP_REPORT, 25L * 1024 * 1024,   // 25 MB
            DocumentType.PROJECT_DEMO_IMAGE, 15L * 1024 * 1024        // 15 MB
    );
    private static final long DEFAULT_MAX_SIZE = 10L * 1024 * 1024;   // 10 MB default

    // Allowed MIME types per DocumentType
    private static final Map<DocumentType, Set<String>> ALLOWED_MIME_TYPES = Map.ofEntries(
            Map.entry(DocumentType.CIN_COPY, Set.of("image/jpeg", "image/png", "image/jpg")),
            Map.entry(DocumentType.CV, Set.of("application/pdf")),
            Map.entry(DocumentType.MOTIVATION_LETTER, Set.of("application/pdf")),
            Map.entry(DocumentType.TRANSCRIPT, Set.of("application/pdf", "image/jpeg", "image/png")),
            Map.entry(DocumentType.UNIVERSITY_CONVENTION, Set.of("application/pdf")),
            Map.entry(DocumentType.STEG_INTERNSHIP_REPORT, Set.of("application/pdf")),
            Map.entry(DocumentType.CAHIER_DES_CHARGES, Set.of("application/pdf")),
            Map.entry(DocumentType.PROJECT_DEMO_IMAGE, Set.of("image/jpeg", "image/png", "image/jpg")),
            Map.entry(DocumentType.PAYMENT_RECEIPT, Set.of("application/pdf", "image/jpeg", "image/png")),
            Map.entry(DocumentType.INTERNSHIP_CERTIFICATE, Set.of("application/pdf")),
            Map.entry(DocumentType.INTERNSHIP_LOGBOOK, Set.of("application/pdf")),
            Map.entry(DocumentType.INTERNSHIP_APPLICATION, Set.of("application/pdf")),
            Map.entry(DocumentType.ASSIGNMENT_LETTER, Set.of("application/pdf")),
            Map.entry(DocumentType.INTERNSHIP_CONVENTION, Set.of("application/pdf")),
            Map.entry(DocumentType.OTHER, Set.of("application/pdf", "image/jpeg", "image/png"))
    );

    public record ValidationResult(String detectedMimeType, String checksum, long sizeBytes) {}

    /**
     * Inspects the file bytes: enforces max size per document type, runs Tika content-type detection,
     * checks for extension/mimeType spoofing, and computes SHA-256.
     */
    public ValidationResult validate(byte[] bytes, String declaredFileName, String declaredContentType, DocumentType type) {
        long maxSize = SIZE_LIMITS.getOrDefault(type, DEFAULT_MAX_SIZE);
        if (bytes.length > maxSize) {
            throw new BusinessRuleException("FILE_TOO_LARGE",
                    String.format("File size (%d bytes) exceeds maximum allowed for %s (%d bytes).",
                            bytes.length, type, maxSize));
        }

        if (bytes.length == 0) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded file cannot be empty.");
        }

        // Tika inspection based on actual file magic bytes
        String detectedMimeType;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            detectedMimeType = tika.detect(is, declaredFileName);
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_ANALYSIS_FAILED", "Failed to inspect file contents: " + e.getMessage());
        }

        // Check for spoofing: if client declared a specific MIME type, ensure compatibility
        if (declaredContentType != null && !declaredContentType.isBlank() && !"application/octet-stream".equals(declaredContentType)) {
            if (!isCompatible(declaredContentType, detectedMimeType)) {
                log.warn("MIME type spoofing detected: declared='{}', detected='{}' for file='{}'",
                        declaredContentType, detectedMimeType, declaredFileName);
                throw new BusinessRuleException("MIME_TYPE_SPOOFING_DETECTED",
                        String.format("Declared content type '%s' does not match detected binary type '%s'.",
                                declaredContentType, detectedMimeType));
            }
        }

        // Check if detected MIME type is allowed for this DocumentType
        Set<String> allowedTypes = ALLOWED_MIME_TYPES.get(type);
        if (allowedTypes != null && !allowedTypes.contains(detectedMimeType.toLowerCase())) {
            throw new BusinessRuleException("INVALID_FILE_TYPE",
                    String.format("File content type '%s' is not permitted for document type '%s'. Allowed: %s",
                            detectedMimeType, type, allowedTypes));
        }

        // Compute SHA-256 checksum
        String checksum = computeSha256(bytes);

        return new ValidationResult(detectedMimeType, checksum, bytes.length);
    }

    private boolean isCompatible(String declared, String detected) {
        String decl = declared.toLowerCase().trim();
        String det = detected.toLowerCase().trim();
        if (decl.equals(det)) return true;
        // Normalize jpeg variants
        if ((decl.equals("image/jpg") || decl.equals("image/jpeg")) &&
            (det.equals("image/jpg") || det.equals("image/jpeg"))) {
            return true;
        }
        return false;
    }

    public static String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
