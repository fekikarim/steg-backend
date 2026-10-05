package tn.steg.backend.companion.domain.service;

/**
 * Domain port: extracts plain text from an uploaded specifications PDF so the
 * AI generator can work on it. The PDF bytes are UNTRUSTED data (S10a
 * prompt-injection rule): extraction returns text only, never instructions.
 */
public interface SpecPdfTextExtractor {

    /**
     * @param pdfBytes raw uploaded bytes (must be a PDF, within the size cap)
     * @param filename original filename, for error messages only
     * @return extracted plain text, trimmed (never null)
     * @throws tn.steg.backend.common.domain.exception.BusinessRuleException
     *         with {@code SPEC_PDF_INVALID} (not a PDF / unreadable),
     *         {@code SPEC_PDF_TOO_LARGE} or {@code SPEC_PDF_EMPTY}
     */
    String extractText(byte[] pdfBytes, String filename);
}
