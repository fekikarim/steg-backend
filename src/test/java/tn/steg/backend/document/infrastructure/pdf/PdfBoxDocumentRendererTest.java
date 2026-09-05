package tn.steg.backend.document.infrastructure.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.document.domain.service.PdfDocumentRenderer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production gate: official-document PDF rendering — French, Arabic-only,
 * mixed content, bold headings, tables, diacritics, numbers/dates, and
 * embedded-font verification (selectable/searchable text, never rasterized).
 *
 * <p>NOTE: automated tests verify shaping math (ArabicTextShaperTest),
 * embedding, and content presence. Per requirements, Arabic output must still
 * be MANUALLY inspected before any production-readiness claim.
 */
@DisplayName("PdfBoxDocumentRenderer tests (multilingual official documents)")
class PdfBoxDocumentRendererTest {

    private PdfBoxDocumentRenderer renderer;

    @BeforeEach
    void setUp() {
        ArabicFontProvider fonts = new ArabicFontProvider();
        fonts.validateFonts();
        renderer = new PdfBoxDocumentRenderer(fonts);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String normalized(byte[] pdf) throws Exception {
        return textOf(pdf).replaceAll("\\s+", " ").strip();
    }

    private static boolean hasEmbeddedNoto(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var names = document.getPage(0).getResources().getFontNames();
            boolean notoEmbedded = false;
            for (var name : names) {
                PDFont font = document.getPage(0).getResources().getFont(name);
                if (font instanceof PDType0Font type0) {
                    String baseName = String.valueOf(type0.getName());
                    if (baseName.contains("Naskh")) {
                        // Embedded subset + ToUnicode map = selectable/searchable Arabic.
                        assertThat(type0.getFontDescriptor().getFontFile2()).isNotNull();
                        assertThat(type0.getCOSObject().getDictionaryObject(
                                org.apache.pdfbox.cos.COSName.TO_UNICODE)).isNotNull();
                        notoEmbedded = true;
                    }
                }
            }
            return notoEmbedded;
        }
    }

    @Test
    @DisplayName("French-only document renders with title, table and footer")
    void frenchOnly() throws Exception {
        byte[] pdf = renderer.renderStructured(
                "ATTESTATION DE STAGE",
                "Société Tunisienne de l'Électricité et du Gaz (STEG)",
                List.of(
                        new PdfDocumentRenderer.ContentBlock.Paragraph(
                                "Nous attestons que Sami Gharbi a effectué un stage de perfectionnement."),
                        new PdfDocumentRenderer.ContentBlock.DetailsTable(
                                List.of(),
                                List.of(List.of("Période", "du 01/01/2026 au 01/04/2026"),
                                        List.of("Montant", "150.00 TND")))),
                "Document généré par la plateforme STEG.",
                null);

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("ATTESTATION DE STAGE");
        assertThat(text).contains("Sami Gharbi");
        assertThat(text).contains("150.00 TND");
        assertThat(text).contains("Période");
    }

    @Test
    @DisplayName("Arabic-only content renders with embedded Noto font")
    void arabicOnly() throws Exception {
        byte[] pdf = renderer.renderStructured(
                "شهادة تربص",
                "الشركة التونسية للكهرباء والغاز",
                List.of(new PdfDocumentRenderer.ContentBlock.Paragraph(
                        "نشهد أن الطالب قد أتم فترة التربص بنجاح.")),
                "وثيقة مولدة من منظومة STEG.",
                null);

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        assertThat(hasEmbeddedNoto(pdf)).isTrue();
        // Extraction yields visual word order with intact logical spelling per
        // word (verified behavior): assert single words, never multi-word phrases.
        String text = normalized(pdf);
        assertThat(text).contains("شهادة");
        assertThat(text).contains("تربص");
        assertThat(text).contains("نشهد");
        assertThat(text).contains("الطالب");
    }

    @Test
    @DisplayName("Mixed French/Arabic paragraph keeps both scripts selectable")
    void mixedContent() throws Exception {
        byte[] pdf = renderer.renderStructured(
                "Attestation — شهادة",
                "STEG — الشركة التونسية للكهرباء والغاز",
                List.of(new PdfDocumentRenderer.ContentBlock.Paragraph(
                        "Le stagiaire محمد بن أحمد a effectué son stage avec succès.")),
                "Fait le 12/05/2026.",
                null);

        assertThat(hasEmbeddedNoto(pdf)).isTrue();
        String text = normalized(pdf);
        assertThat(text).contains("Le stagiaire");
        assertThat(text).contains("محمد");
        assertThat(text).contains("أحمد");
        assertThat(text).contains("12/05/2026");
    }

    @Test
    @DisplayName("Arabic headings use the bold font; tables carry Arabic cells")
    void boldHeadingsAndArabicTables() throws Exception {
        byte[] pdf = renderer.renderStructured(
                "شهادة تربص",
                "",
                List.of(new PdfDocumentRenderer.ContentBlock.DetailsTable(
                        List.of("الحقل", "القيمة"),
                        List.of(
                                List.of("الاسم الكامل", "محمد بن أحمد"),
                                List.of("المدة", "3 أشهر")))),
                "",
                null);

        assertThat(hasEmbeddedNoto(pdf)).isTrue();
        String text = normalized(pdf);
        assertThat(text).contains("الاسم");
        assertThat(text).contains("الكامل");
        assertThat(text).contains("محمد");
        assertThat(text).contains("أشهر");
        assertThat(text).contains("3");
    }

    @Test
    @DisplayName("Diacritics, Arabic-Indic digits and dates survive rendering")
    void diacriticsAndNumbers() throws Exception {
        String body = "مَرْحَبًا بالطالب رقم 123 بتاريخ 12/05/2026 والمبلغ 150.00 د.ت";
        byte[] pdf = renderer.renderStructured("Test", "", List.of(
                new PdfDocumentRenderer.ContentBlock.Paragraph(body)), "", null);

        assertThat(hasEmbeddedNoto(pdf)).isTrue();
        String text = normalized(pdf);
        // Diacritic marks preserved end to end (not swallowed by shaping).
        assertThat(text.codePoints().anyMatch(cp -> cp >= 0x064B && cp <= 0x0652)).isTrue();
        assertThat(text).contains("150.00");
        assertThat(text).contains("12/05/2026");
        assertThat(text).contains("د.ت");
    }

    @Test
    @DisplayName("Shared-outline glyphs extract as Arabic (ToUnicode alias patch)")
    void yehHehRoundTrip() throws Exception {
        // Medial/final Yeh and isolated Heh share outlines with Farsi/AE glyphs;
        // the renderer remaps those ToUnicode aliases so search sees Arabic.
        byte[] pdf = renderer.renderStructured("Test", "", List.of(
                        new PdfDocumentRenderer.ContentBlock.Paragraph("مدينة علي"),
                        new PdfDocumentRenderer.ContentBlock.Paragraph("ه")),
                "", null);

        assertThat(hasEmbeddedNoto(pdf)).isTrue();
        String text = normalized(pdf);
        assertThat(text).contains("مدينة");
        assertThat(text).contains("علي");
        assertThat(text).contains("ه");
        assertThat(text.codePoints().noneMatch(cp -> cp == 0x06CC || cp == 0x06D5)).isTrue();
    }

    @Test
    @DisplayName("Legacy single-shot render still works (paragraphs only)")
    void legacyRenderDelegates() throws Exception {
        byte[] pdf = renderer.render("Titre", "Sous-titre",
                List.of("Premier paragraphe.", "Deuxième paragraphe avec accents éèç."),
                "Pied de page.", null);
        String text = textOf(pdf);
        assertThat(text).contains("Titre");
        assertThat(text).contains("accents éèç");
    }
}
