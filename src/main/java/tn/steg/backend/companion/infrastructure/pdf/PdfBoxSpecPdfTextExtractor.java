package tn.steg.backend.companion.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.companion.domain.service.SpecPdfTextExtractor;

import java.io.IOException;

/**
 * PDFBox implementation of {@link SpecPdfTextExtractor} (S10a).
 *
 * <p>Security: PDF magic bytes are verified, the upload is size-capped and
 * page-capped, and
 * extraction is text-only — embedded JavaScript, actions or instructions in
 * the document can never execute or reach the AI as instructions (the text is
 * embedded in the prompt as delimited DATA, see AiTaskDraftService).
 */
@Slf4j
@Component
public class PdfBoxSpecPdfTextExtractor implements SpecPdfTextExtractor {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * Maximum accepted page count (audit S11 assumption: generous ceiling so
     * legitimate specifications pass while hundred-page dumps are refused
     * before prompt assembly).
     */
    static final int MAX_PAGES = 50;
    private final long maxBytes;

    public PdfBoxSpecPdfTextExtractor(
            @Value("${steg.storage.max-file-size-mb:25}") int maxFileSizeMb) {
        this.maxBytes = Math.max(1, maxFileSizeMb) * 1024L * 1024L;
    }

    @Override
    public String extractText(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new BusinessRuleException("SPEC_PDF_INVALID",
                    "The specifications document is empty: upload a PDF file.");
        }
        if (pdfBytes.length > maxBytes) {
            throw new BusinessRuleException("SPEC_PDF_TOO_LARGE",
                    "The specifications PDF exceeds the " + (maxBytes / 1024 / 1024)
                            + " MB upload limit.");
        }
        if (!hasPdfMagic(pdfBytes)) {
            throw new BusinessRuleException("SPEC_PDF_INVALID",
                    "The uploaded file '" + safeName(filename) + "' is not a PDF.");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new BusinessRuleException("SPEC_PDF_TOO_LONG",
                        "The specifications PDF has " + document.getNumberOfPages()
                                + " pages, above the " + MAX_PAGES + "-page limit.");
            }
            String text = new PDFTextStripper().getText(document);
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.isEmpty()) {
                throw new BusinessRuleException("SPEC_PDF_EMPTY",
                        "No readable text was found in the specifications PDF "
                                + "(scanned images without text cannot be processed).");
            }
            // Bound the prompt: the model only needs the substance.
            int capped = Math.min(trimmed.length(), 60_000);
            return trimmed.substring(0, capped);
        } catch (IOException ex) {
            throw new BusinessRuleException("SPEC_PDF_INVALID",
                    "The specifications PDF could not be read: " + ex.getMessage());
        }
    }

    private static boolean hasPdfMagic(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.pdf";
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 80 ? base.substring(0, 80) : base;
    }
}
