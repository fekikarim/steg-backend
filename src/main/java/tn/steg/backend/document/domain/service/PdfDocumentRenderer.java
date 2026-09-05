package tn.steg.backend.document.domain.service;

import java.util.List;

/**
 * Port for server-side official-document PDF rendering (Phase A11).
 */
public interface PdfDocumentRenderer {

    /** One renderable content unit: flowing text or a bordered details grid. */
    sealed interface ContentBlock permits ContentBlock.Paragraph, ContentBlock.DetailsTable {

        /** A flowing paragraph (blank line separation handled by the renderer). */
        record Paragraph(String text) implements ContentBlock {
        }

        /**
         * A bordered two-part grid (label column + value column).
         * Headers may be empty (headerless label/value layout).
         */
        record DetailsTable(List<String> headers, List<List<String>> rows) implements ContentBlock {
        }
    }

    /**
     * Renders one official document to PDF bytes (A4, branding header,
     * title, body, footer).
     *
     * @param title          centered bold title
     * @param subtitle       centered subtitle (may be blank)
     * @param bodyParagraphs already-substituted body paragraphs
     * @param footer         small footer text (may be blank)
     * @param logoPngBytes   official logo bytes for the header
     * @return the PDF document bytes (starting with {@code %PDF-})
     */
    default byte[] render(String title, String subtitle, List<String> bodyParagraphs,
                          String footer, byte[] logoPngBytes) {
        List<ContentBlock> blocks = new java.util.ArrayList<>(bodyParagraphs.size());
        for (String paragraph : bodyParagraphs) {
            blocks.add(new ContentBlock.Paragraph(paragraph));
        }
        return renderStructured(title, subtitle, blocks, footer, logoPngBytes);
    }

    /**
     * Renders a structured document mixing paragraphs and details tables.
     * Arabic runs use the embedded Noto Naskh Arabic fonts; Latin runs use
     * Helvetica. All text stays selectable/searchable (shaped, never rasterized).
     */
    byte[] renderStructured(String title, String subtitle, List<ContentBlock> blocks,
                            String footer, byte[] logoPngBytes);
}
