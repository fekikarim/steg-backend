package tn.steg.backend.companion.infrastructure.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.common.domain.exception.BusinessRuleException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S11 pre-check (c): the specs-PDF size cap and page cap, proven without Spring.
 */
@DisplayName("Specs-PDF extraction caps")
class PdfBoxSpecPdfTextExtractorTest {

    private final PdfBoxSpecPdfTextExtractor extractor = new PdfBoxSpecPdfTextExtractor(25);

    private static byte[] pdfWithPages(int pages, boolean withText) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (withText) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.beginText();
                        stream.newLineAtOffset(50, 700);
                        stream.showText("Specification line on page " + (i + 1));
                        stream.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a normal specs PDF extracts its text")
    void validPdfExtractsText() throws Exception {
        String text = extractor.extractText(pdfWithPages(2, true), "specs.pdf");
        assertThat(text).contains("Specification line on page 1");
    }

    @Test
    @DisplayName("non-PDF bytes are refused before parsing")
    void nonPdfIsRefused() {
        byte[] notPdf = "just some text, not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> extractor.extractText(notPdf, "notes.txt"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a PDF");
    }

    @Test
    @DisplayName("uploads above the megabyte cap are refused with SPEC_PDF_TOO_LARGE")
    void oversizeUploadIsRefused() {
        byte[] huge = new byte[26 * 1024 * 1024];
        Arrays.fill(huge, (byte) '%');
        // Keep valid magic so the size gate (not the magic gate) fires.
        huge[0] = '%';
        huge[1] = 'P';
        huge[2] = 'D';
        huge[3] = 'F';
        huge[4] = '-';
        assertThatThrownBy(() -> extractor.extractText(huge, "huge.pdf"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SPEC_PDF_TOO_LARGE");
    }

    @Test
    @DisplayName("a scanned-style PDF without text is refused with SPEC_PDF_EMPTY")
    void textlessPdfIsRefused() throws Exception {
        assertThatThrownBy(() -> extractor.extractText(pdfWithPages(3, false), "scan.pdf"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SPEC_PDF_EMPTY");
    }

    @Test
    @DisplayName("a PDF above the page cap is refused with SPEC_PDF_TOO_LONG")
    void overPageCapIsRefused() throws Exception {
        assertThatThrownBy(() ->
                extractor.extractText(pdfWithPages(PdfBoxSpecPdfTextExtractor.MAX_PAGES + 1, true), "dump.pdf"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SPEC_PDF_TOO_LONG");
    }

    @Test
    @DisplayName("a PDF exactly at the page cap still extracts")
    void atPageCapStillExtracts() throws Exception {
        String text = extractor.extractText(pdfWithPages(PdfBoxSpecPdfTextExtractor.MAX_PAGES, true), "big.pdf");
        assertThat(text).contains("Specification line on page 1");
    }
}
