package tn.steg.backend.document.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.document.domain.model.DocumentType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentValidationServiceTest {

    private DocumentValidationService validationService;

    @BeforeEach
    void setUp() {
        validationService = new DocumentValidationService();
    }

    @Test
    @DisplayName("Should successfully validate a valid PDF file for CV")
    void shouldValidateValidPdf() {
        byte[] pdfBytes = "%PDF-1.4 header and content for testing".getBytes(StandardCharsets.UTF_8);

        DocumentValidationService.ValidationResult result = validationService.validate(
                pdfBytes,
                "resume.pdf",
                "application/pdf",
                DocumentType.CV
        );

        assertThat(result.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(result.checksum()).isNotEmpty();
        assertThat(result.sizeBytes()).isEqualTo(pdfBytes.length);
    }

    @Test
    @DisplayName("Should detect spoofed file type when text file is disguised as PDF")
    void shouldDetectSpoofedFileType() {
        byte[] plainTextBytes = "This is a plain text file pretending to be a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validationService.validate(
                plainTextBytes,
                "resume.pdf",
                "application/pdf",
                DocumentType.CV
        ))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not match detected binary type");
    }

    @Test
    @DisplayName("Should reject disallowed MIME type for CIN_COPY (e.g. PDF not allowed)")
    void shouldRejectDisallowedMimeTypeForCinCopy() {
        byte[] pdfBytes = "%PDF-1.4 header".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validationService.validate(
                pdfBytes,
                "cin.pdf",
                "application/pdf",
                DocumentType.CIN_COPY
        ))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not permitted for document type 'CIN_COPY'");
    }

    @Test
    @DisplayName("Should reject file exceeding DocumentType size limit")
    void shouldRejectOversizedFile() {
        // CIN_COPY is max 5MB. Let's create a 6MB array
        byte[] largeBytes = new byte[6 * 1024 * 1024];

        assertThatThrownBy(() -> validationService.validate(
                largeBytes,
                "huge_cin.jpg",
                "image/jpeg",
                DocumentType.CIN_COPY
        ))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds maximum allowed for CIN_COPY");
    }

    @Test
    @DisplayName("Should compute accurate SHA-256 checksum")
    void shouldComputeAccurateSha256() {
        byte[] data = "test-sha256-content".getBytes(StandardCharsets.UTF_8);
        String checksum = DocumentValidationService.computeSha256(data);

        // SHA-256 is 64 hex characters
        assertThat(checksum).hasSize(64);
        // Consistent hash
        assertThat(DocumentValidationService.computeSha256(data)).isEqualTo(checksum);
    }
}
