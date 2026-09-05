package tn.steg.backend.document.infrastructure.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.document.domain.service.PdfDocumentRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDFBox implementation of {@link PdfDocumentRenderer}.
 *
 * <p>Layout contract (stable for tests): A4, 56pt margins, official logo
 * centered on top, centered bold title, centered subtitle, flowing paragraphs
 * and bordered details tables (header + zebra rows, header repeated across
 * page breaks), small footer on the last page.
 *
 * <p>Multilingual rendering: every logical line is shaped + reordered to
 * visual order ({@link ArabicTextShaper}, offline-safe), then split into
 * font runs — Arabic-script runs use the embedded Noto Naskh Arabic fonts,
 * everything else uses Helvetica. Arabic stays selectable/searchable text
 * (shaped glyphs + embedded subset + ToUnicode map), never rasterized.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfBoxDocumentRenderer implements PdfDocumentRenderer {

    private static final float MARGIN = 56f;
    private static final float LOGO_MAX_WIDTH = 220f;
    private static final float LOGO_MAX_HEIGHT = 70f;
    private static final float TITLE_SIZE = 18f;
    private static final float SUBTITLE_SIZE = 12f;
    private static final float BODY_SIZE = 11f;
    private static final float FOOTER_SIZE = 9f;
    private static final float CELL_SIZE = 10.5f;
    private static final float CELL_PADDING = 5f;
    private static final float LINE_GAP = 4f;

    private final ArabicFontProvider fontProvider;

    /** One visual run: already shaped+ordered text in a single font. */
    private record Run(String text, PDFont font, float size) {
    }

    private static final class Fonts {
        final PDType1Font helvBold;
        final PDType1Font helvReg;
        final PDType1Font helvOblique;
        final PDType0Font arabReg;
        final PDType0Font arabBold;

        Fonts(PDDocument document, ArabicFontProvider provider) throws IOException {
            this.helvBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            this.helvReg = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            this.helvOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
            try {
                this.arabReg = PDType0Font.load(document, new ByteArrayInputStream(provider.regular()));
                this.arabBold = PDType0Font.load(document, new ByteArrayInputStream(provider.bold()));
            } catch (IOException e) {
                throw new BusinessRuleException("ARABIC_FONT_UNAVAILABLE",
                        "Failed to embed the Arabic fonts into the PDF: " + e.getMessage());
            }
        }

        PDFont latin(boolean bold) {
            return bold ? helvBold : helvReg;
        }

        PDFont arabic(boolean bold) {
            return bold ? arabBold : arabReg;
        }
    }

    /** Mutable cursor: current page + baseline y. Pages roll over on demand. */
    private static final class Cursor {
        final PDDocument document;
        Page page;
        float y;

        Cursor(PDDocument document, Page page, float y) {
            this.document = document;
            this.page = page;
            this.y = y;
        }

        void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN + 20f) {
                page.close();
                page = new Page(document);
                y = page.height - MARGIN;
            }
        }
    }

    private static final class Page {
        final PDPage pdPage;
        final PDPageContentStream stream;
        final float width;
        final float height;

        Page(PDDocument document) throws IOException {
            this.pdPage = new PDPage(PDRectangle.A4);
            document.addPage(pdPage);
            this.stream = new PDPageContentStream(document, pdPage);
            this.width = PDRectangle.A4.getWidth();
            this.height = PDRectangle.A4.getHeight();
        }

        void close() throws IOException {
            stream.close();
        }
    }

    private static float contentWidth() {
        return PDRectangle.A4.getWidth() - 2 * MARGIN;
    }

    @Override
    public byte[] renderStructured(String title, String subtitle, List<ContentBlock> blocks,
                                   String footer, byte[] logoPngBytes) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Fonts fonts = new Fonts(document, fontProvider);
            Cursor cursor = new Cursor(document, new Page(document), PDRectangle.A4.getHeight() - MARGIN);

            if (logoPngBytes != null && logoPngBytes.length > 0) {
                cursor.y = drawLogo(document, cursor.page, logoPngBytes, cursor.y) - 18f;
            }
            cursor.y = drawCentered(cursor, fonts, title, true, TITLE_SIZE, cursor.y) - 8f;
            if (subtitle != null && !subtitle.isBlank()) {
                cursor.y = drawCentered(cursor, fonts, subtitle, true, SUBTITLE_SIZE, cursor.y) - 6f;
            }
            cursor.y -= 12f;
            for (ContentBlock block : blocks) {
                if (block instanceof ContentBlock.Paragraph paragraph) {
                    drawParagraph(cursor, fonts, paragraph.text(), false, BODY_SIZE);
                    cursor.y -= 8f;
                } else if (block instanceof ContentBlock.DetailsTable table) {
                    drawTable(cursor, fonts, table);
                    cursor.y -= 10f;
                }
            }
            cursor.page.close();
            drawFooter(document, fonts, footer);
            // PDFBox finalizes font subsets (and their ToUnicode maps) during
            // save, so alias repair must happen between two saves: draft once,
            // patch the finalized maps, then produce the final bytes.
            byte[] draft;
            try (ByteArrayOutputStream tmp = new ByteArrayOutputStream()) {
                document.save(tmp);
                draft = tmp.toByteArray();
            }
            byte[] pdf;
            try (PDDocument finalized = org.apache.pdfbox.Loader.loadPDF(draft)) {
                patchNaskhToUnicode(finalized);
                finalized.save(out);
                pdf = out.toByteArray();
            }
            log.debug("Rendered official PDF document ({} bytes)", pdf.length);
            return pdf;
        } catch (IOException e) {
            throw new BusinessRuleException("PDF_RENDER_FAILED",
                    "Failed to render the official PDF document: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Visual text pipeline (shape → reorder → font runs → wrap)
    // ------------------------------------------------------------------

    private List<Run> toRuns(Fonts fonts, String visual, boolean bold, float size) {
        List<Run> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentArabic = false;
        boolean started = false;
        for (int i = 0; i < visual.length(); ) {
            int cp = visual.codePointAt(i);
            boolean arabic = ArabicTextShaper.needsArabicFont(cp);
            if (!started) {
                currentArabic = arabic;
                started = true;
            }
            if (arabic != currentArabic) {
                runs.add(new Run(current.toString(), currentArabic ? fonts.arabic(bold) : fonts.latin(bold), size));
                current.setLength(0);
                currentArabic = arabic;
            }
            current.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (!current.isEmpty() || runs.isEmpty()) {
            runs.add(new Run(current.toString(), currentArabic ? fonts.arabic(bold) : fonts.latin(bold), size));
        }
        return runs;
    }

    private float runWidth(Run run) throws IOException {
        return run.font().getStringWidth(run.text()) / 1000f * run.size();
    }

    private float runsWidth(List<Run> runs) throws IOException {
        float width = 0f;
        for (Run run : runs) {
            if (!run.text().isEmpty()) {
                width += runWidth(run);
            }
        }
        return width;
    }

    private float spaceWidth(Fonts fonts, float size) throws IOException {
        return fonts.helvReg.getStringWidth(" ") / 1000f * size;
    }

    /**
     * Greedy word wrap on the logical line. Each wrapped line is converted to
     * visual font runs (shaped + reordered); overlong single tokens are
     * hard-broken by characters in visual order.
     */
    private List<List<Run>> wrapRuns(Fonts fonts, String logical, boolean bold, float size, float maxWidth)
            throws IOException {
        List<List<Run>> lines = new ArrayList<>();
        List<Run> current = new ArrayList<>();
        float currentWidth = 0f;
        boolean firstWord = true;
        String[] words = logical == null ? new String[0] : logical.split("\\s+");
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            List<Run> wordRuns = toRuns(fonts, ArabicTextShaper.render(word), bold, size);
            float wordWidth = runsWidth(wordRuns);
            float gap = firstWord ? 0f : spaceWidth(fonts, size);
            if (!firstWord && currentWidth + gap + wordWidth > maxWidth) {
                if (wordWidth > maxWidth) {
                    // Overlong token (e.g. a reference): hard-break, then continue.
                    List<Run> pending = new ArrayList<>(wordRuns);
                    while (!pending.isEmpty()) {
                        if (!current.isEmpty()) {
                            lines.add(current);
                            current = new ArrayList<>();
                            currentWidth = 0f;
                        }
                        current = takeFittingRuns(fonts, pending, size, maxWidth);
                        currentWidth = runsWidth(current);
                        pending = dropTaken(pending, current);
                        if (current.isEmpty()) {
                            break; // defensive: never emit empty lines forever
                        }
                    }
                    firstWord = current.isEmpty();
                    continue;
                }
                lines.add(current);
                current = new ArrayList<>();
                currentWidth = 0f;
                firstWord = true;
                gap = 0f;
            }
            if (!firstWord) {
                current.add(new Run(" ", fonts.helvReg, size));
                currentWidth += gap;
            }
            current.addAll(wordRuns);
            currentWidth += wordWidth;
            firstWord = false;
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current);
        }
        return lines;
    }

    /** Takes a visual-order prefix of runs fitting maxWidth (char-granular). */
    private List<Run> takeFittingRuns(Fonts fonts, List<Run> runs, float size, float maxWidth) throws IOException {
        List<Run> taken = new ArrayList<>();
        float width = 0f;
        for (Run run : runs) {
            StringBuilder piece = new StringBuilder();
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                String ch = new String(Character.toChars(cp));
                float w = run.font().getStringWidth(ch) / 1000f * size;
                if (width + w > maxWidth && (width > 0 || !taken.isEmpty())) {
                    if (!piece.isEmpty()) {
                        taken.add(new Run(piece.toString(), run.font(), size));
                    }
                    return taken;
                }
                piece.append(ch);
                width += w;
                i += Character.charCount(cp);
            }
            if (!piece.isEmpty()) {
                taken.add(new Run(piece.toString(), run.font(), size));
            }
        }
        return taken;
    }

    /** Removes the taken prefix (by char counts) from visual runs. */
    private List<Run> dropTaken(List<Run> runs, List<Run> taken) {
        List<Run> rebuilt = new ArrayList<>();
        int ti = 0;
        int toff = 0;
        for (Run run : runs) {
            int off = 0;
            while (ti < taken.size() && off < run.text().length()) {
                int need = taken.get(ti).text().length() - toff;
                int have = run.text().length() - off;
                int step = Math.min(need, have);
                off += step;
                toff += step;
                if (toff >= taken.get(ti).text().length()) {
                    ti++;
                    toff = 0;
                }
            }
            if (off < run.text().length()) {
                rebuilt.add(new Run(run.text().substring(off), run.font(), run.size()));
            }
        }
        return rebuilt;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private float drawLogo(PDDocument document, Page page, byte[] logoPng, float y) throws IOException {
        PDImageXObject image = PDImageXObject.createFromByteArray(document, logoPng, "steg-logo");
        float scale = Math.min(LOGO_MAX_WIDTH / image.getWidth(), LOGO_MAX_HEIGHT / image.getHeight());
        float w = image.getWidth() * scale;
        float h = image.getHeight() * scale;
        float x = (page.width - w) / 2f;
        page.stream.drawImage(image, x, y - h, w, h);
        return y - h;
    }

    private float drawCentered(Cursor cursor, Fonts fonts, String text,
                               boolean bold, float size, float y) throws IOException {
        for (List<Run> line : wrapRuns(fonts, text, bold, size, contentWidth())) {
            cursor.ensureSpace(size + LINE_GAP);
            float lineWidth = runsWidth(line);
            float x = MARGIN + Math.max(0f, (contentWidth() - lineWidth) / 2f);
            drawRuns(cursor.page, line, x, cursor.y);
            cursor.y -= size + LINE_GAP;
            y = cursor.y;
        }
        return y;
    }

    private void drawParagraph(Cursor cursor, Fonts fonts, String text,
                               boolean bold, float size) throws IOException {
        for (List<Run> line : wrapRuns(fonts, text, bold, size, contentWidth())) {
            cursor.ensureSpace(size + LINE_GAP);
            drawRuns(cursor.page, line, MARGIN, cursor.y);
            cursor.y -= size + LINE_GAP;
        }
    }

    private void drawRuns(Page page, List<Run> line, float x, float y) throws IOException {
        page.stream.beginText();
        drawRunLine(page.stream, line, x, y);
        page.stream.endText();
    }

    /**
     * Draws one visual line. NOTE: {@code newLineAtOffset} moves RELATIVE to
     * the current text position, so the first move is absolute and every
     * subsequent move is a delta — absolute moves would stack and push each
     * font run onto its own descending line.
     */
    private void drawRunLine(PDPageContentStream stream, List<Run> line, float x, float y) throws IOException {
        float cursor = x;
        float matrixX = 0f;
        boolean positioned = false;
        PDFont currentFont = null;
        float currentSize = 0f;
        StringBuilder pending = new StringBuilder();
        for (Run run : line) {
            if (run.text().isEmpty()) {
                continue;
            }
            if (currentFont != null && (!currentFont.equals(run.font()) || currentSize != run.size())) {
                cursor = flushRun(stream, pending.toString(), currentFont, currentSize, cursor, y, matrixX, positioned);
                matrixX = cursor;
                positioned = true;
                pending.setLength(0);
            }
            currentFont = run.font();
            currentSize = run.size();
            pending.append(run.text());
        }
        if (pending.length() > 0 && currentFont != null) {
            flushRun(stream, pending.toString(), currentFont, currentSize, cursor, y, matrixX, positioned);
        }
    }

    /**
     * Draws one same-font span; returns the new absolute cursor x. The text
     * matrix sits where the previous span ended, so moves after the first are
     * deltas — and zero-delta moves are skipped entirely, because redundant
     * {@code 0 0 Td} operators confuse PDF text extraction (words merge and
     * glyphs drop out of the extracted reading order).
     */
    private float flushRun(PDPageContentStream stream, String text, PDFont font, float size,
                           float cursor, float y, float matrixX, boolean positioned) throws IOException {
        stream.setFont(font, size);
        if (!positioned) {
            stream.newLineAtOffset(cursor, y);
        } else if (cursor != matrixX) {
            stream.newLineAtOffset(cursor - matrixX, 0f);
        }
        stream.showText(text);
        return cursor + font.getStringWidth(text) / 1000f * size;
    }

    // ------------------------------------------------------------------
    // Tables (label/value grids, header repeat across page breaks)
    // ------------------------------------------------------------------

    private void drawTable(Cursor cursor, Fonts fonts, ContentBlock.DetailsTable table) throws IOException {
        int cols = 0;
        if (!table.headers().isEmpty()) {
            cols = table.headers().size();
        }
        for (List<String> row : table.rows()) {
            cols = Math.max(cols, row.size());
        }
        if (cols == 0) {
            return;
        }
        float colWidth = contentWidth() / cols;
        boolean stripe = false;

        if (!table.headers().isEmpty()) {
            drawTableRow(cursor, fonts, table.headers(), true, cols, colWidth);
        }
        for (List<String> row : table.rows()) {
            int maxLines = 1;
            for (int c = 0; c < cols; c++) {
                String cell = c < row.size() && row.get(c) != null ? row.get(c) : "";
                maxLines = Math.max(maxLines,
                        wrapRuns(fonts, cell, c == 0, CELL_SIZE, colWidth - 2 * CELL_PADDING).size());
            }
            float rowHeight = maxLines * (CELL_SIZE + LINE_GAP) + 2 * CELL_PADDING;
            Page before = cursor.page;
            cursor.ensureSpace(rowHeight);
            if (cursor.page != before && !table.headers().isEmpty()) {
                // Page break inside the table: repeat the header on the new page.
                drawTableRow(cursor, fonts, table.headers(), true, cols, colWidth);
            }
            float cx = MARGIN;
            for (int c = 0; c < cols; c++) {
                String cell = c < row.size() && row.get(c) != null ? row.get(c) : "";
                if (stripe) {
                    fillCell(cursor.page, cx, cursor.y - rowHeight, colWidth, rowHeight, false);
                } else {
                    strokeCell(cursor.page, cx, cursor.y - rowHeight, colWidth, rowHeight);
                }
                drawCellText(cursor.page, fonts, cell, c == 0, cx, cursor.y - rowHeight, colWidth, rowHeight);
                cx += colWidth;
            }
            cursor.y -= rowHeight;
            stripe = !stripe;
        }
    }

    private void drawTableRow(Cursor cursor, Fonts fonts, List<String> cells, boolean header,
                              int cols, float colWidth)
            throws IOException {
        int maxLines = 1;
        for (int c = 0; c < cols; c++) {
            String cell = c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
            maxLines = Math.max(maxLines,
                    wrapRuns(fonts, cell, true, CELL_SIZE, colWidth - 2 * CELL_PADDING).size());
        }
        float rowHeight = maxLines * (CELL_SIZE + LINE_GAP) + 2 * CELL_PADDING;
        cursor.ensureSpace(rowHeight);
        float cx = MARGIN;
        for (int c = 0; c < cols; c++) {
            String cell = c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
            fillCell(cursor.page, cx, cursor.y - rowHeight, colWidth, rowHeight, header);
            drawCellText(cursor.page, fonts, cell, true, cx, cursor.y - rowHeight, colWidth, rowHeight);
            cx += colWidth;
        }
        cursor.y -= rowHeight;
    }

    private void fillCell(Page page, float x, float y, float w, float h, boolean header) throws IOException {
        if (header) {
            page.stream.setNonStrokingColor(0.88f, 0.90f, 0.94f);
        } else {
            page.stream.setNonStrokingColor(0.96f, 0.96f, 0.96f);
        }
        page.stream.addRect(x, y, w, h);
        page.stream.fill();
        page.stream.setNonStrokingColor(0f, 0f, 0f);
        strokeCell(page, x, y, w, h);
    }

    private void strokeCell(Page page, float x, float y, float w, float h) throws IOException {
        page.stream.setStrokingColor(0.55f, 0.55f, 0.55f);
        page.stream.setLineWidth(0.6f);
        page.stream.addRect(x, y, w, h);
        page.stream.stroke();
        page.stream.setStrokingColor(0f, 0f, 0f);
    }

    private void drawCellText(Page page, Fonts fonts, String cell, boolean bold,
                              float x, float y, float width, float height) throws IOException {
        float lineHeight = CELL_SIZE + LINE_GAP;
        float cy = y + height - CELL_PADDING - CELL_SIZE;
        for (List<Run> line : wrapRuns(fonts, cell, bold, CELL_SIZE, width - 2 * CELL_PADDING)) {
            drawRuns(page, line, x + CELL_PADDING, cy);
            cy -= lineHeight;
        }
    }

    private void drawFooter(PDDocument document, Fonts fonts, String footer) throws IOException {
        if (footer == null || footer.isBlank()) {
            return;
        }
        PDPage last = document.getPage(document.getNumberOfPages() - 1);
        try (PDPageContentStream footerStream = new PDPageContentStream(
                document, last, PDPageContentStream.AppendMode.APPEND, true, true)) {
            float y = MARGIN - 18f;
            for (List<Run> line : wrapRuns(fonts, footer, false, FOOTER_SIZE, contentWidth())) {
                float lineWidth = runsWidth(line);
                float x = MARGIN + Math.max(0f, (contentWidth() - lineWidth) / 2f);
                footerStream.beginText();
                drawRunLine(footerStream, line, x, y);
                footerStream.endText();
                y -= FOOTER_SIZE + 2f;
            }
        }
    }

    /**
     * Repairs ToUnicode aliases for glyph outlines that Noto Naskh shares
     * across languages. Verified against the shipped font (cmap reverse map):
     * the Arabic Yeh initial/medial outlines are shared with Farsi Yeh, Alef
     * Maksura final with Farsi Yeh final, and Heh isolated with AE — and the
     * subsetter labels those CIDs with the Farsi/AE codepoints, so extracted
     * (searched) text shows the wrong letters. Our pipeline only ever emits
     * Arabic presentation forms, so remapping those destinations to the Arabic
     * base letters is exact here. Base letters match the normalization the
     * extractor already applies to every other presentation form.
     */
    private void patchNaskhToUnicode(PDDocument document) throws IOException {
        Map<String, String> fixes = Map.of(
                "FBFD", "0649", // Alef Maksura final (shared outline with Farsi Yeh final)
                "FBFE", "064A", // Arabic Yeh initial (shared outline with Farsi Yeh initial)
                "FBFF", "064A", // Arabic Yeh medial (shared outline with Farsi Yeh medial)
                "06D5", "0647" // Heh isolated (shared outline with AE)
        );
        java.util.Set<org.apache.pdfbox.cos.COSBase> done = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (PDPage page : document.getPages()) {
            if (page.getResources() == null) {
                continue;
            }
            for (org.apache.pdfbox.cos.COSName name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                if (!(font instanceof PDType0Font)
                        || font.getName() == null
                        || !String.valueOf(font.getName()).contains("Naskh")) {
                    continue;
                }
                org.apache.pdfbox.cos.COSDictionary fontDict = ((PDType0Font) font).getCOSObject();
                org.apache.pdfbox.cos.COSBase tu = fontDict.getDictionaryObject(org.apache.pdfbox.cos.COSName.TO_UNICODE);
                if (!(tu instanceof org.apache.pdfbox.cos.COSStream stream) || !done.add(stream)) {
                    continue;
                }
                String cmap;
                try (InputStream in = stream.createInputStream()) {
                    cmap = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                String patched = patchCmapDestinations(cmap, fixes);
                if (!patched.equals(cmap)) {
                    org.apache.pdfbox.pdmodel.common.PDStream replacement = new org.apache.pdfbox.pdmodel.common.PDStream(
                            document,
                            new ByteArrayInputStream(patched.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)),
                            org.apache.pdfbox.cos.COSName.FLATE_DECODE);
                    fontDict.setItem(org.apache.pdfbox.cos.COSName.TO_UNICODE, replacement.getCOSObject());
                    log.debug("Patched Naskh ToUnicode aliases for searchable Arabic Yeh/Heh");
                }
            }
        }
    }

    /** Rewrites aliased destination tokens (last hex token per mapping line). */
    static String patchCmapDestinations(String cmap, Map<String, String> fixes) {
        // Normalize keys once: CMap hex is case-insensitive, our table is uppercase.
        Map<String, String> table = new java.util.HashMap<>();
        fixes.forEach((key, value) -> table.put(key.toUpperCase(), value));
        String[] lines = cmap.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("begin") || line.startsWith("end") || line.startsWith("/")
                    || !line.startsWith("<")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length < 2 || tokens.length > 3) {
                continue;
            }
            boolean hexLine = true;
            for (String token : tokens) {
                if (!token.matches("<[0-9A-Fa-f]+>")) {
                    hexLine = false;
                    break;
                }
            }
            if (!hexLine) {
                continue;
            }
            String dst = tokens[tokens.length - 1];
            String replacement = table.get(dst.substring(1, dst.length() - 1).toUpperCase());
            if (replacement != null) {
                tokens[tokens.length - 1] = "<" + replacement + ">";
                lines[i] = String.join(" ", tokens);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * WinAnsi/Latin pass-through plus all Arabic ranges (they take the embedded
     * Noto fonts). Anything else becomes '?'.
     * TODO — STEG VALIDATION REQUIRED: embedded Unicode font for Arabic is now
     * implemented; remaining TODO is STEG validation of Arabic wording.
     */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (ArabicTextShaper.needsArabicFont(cp)
                    || (cp >= 0x20 && cp <= 0x7E) || cp == '\n' || cp == '\t'
                    || (cp >= 0xA0 && cp <= 0xFF) || cp == 0x20AC || cp == 0x0152
                    || cp == 0x0153 || cp == 0x0178) {
                out.appendCodePoint(cp);
            } else {
                out.append('?');
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
