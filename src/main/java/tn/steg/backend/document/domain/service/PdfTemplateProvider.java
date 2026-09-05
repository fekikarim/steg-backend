package tn.steg.backend.document.domain.service;

import java.util.List;
import java.util.Map;

/**
 * Port for swappable official-document wording (Phase A11).
 *
 * <p>Deliberately file-based instead of a template engine (Thymeleaf/FreeMarker)
 * or a config entity: the two official documents are static by nature, and a
 * plain text file lets STEG replace the wording without touching Java logic or
 * adding a dependency. Unknown placeholders are left as-is so a wording update
 * can never silently drop content.
 */
public interface PdfTemplateProvider {

    record ResolvedTemplate(String title, String subtitle, List<String> bodyParagraphs, String footer) {
    }

    /** One substituted label/value row for a details table. */
    record TableRow(String label, String value) {
    }

    record StructuredTemplate(String title, String subtitle, List<String> introParagraphs,
                              List<TableRow> detailRows, List<String> bodyParagraphs, String footer) {
    }

    /**
     * Loads {@code templates/<logicalName>.txt} and substitutes
     * {@code {{placeholders}}} from the given values.
     */
    ResolvedTemplate resolve(String logicalName, Map<String, String> values);

    /**
     * Loads {@code [TITLE]/[SUBTITLE]/[INTRO]/[DETAILS]/[BODY]/[FOOTER]} sections.
     * DETAILS lines have the form {@code Label : {{key}}} and become table rows.
     */
    StructuredTemplate resolveStructured(String logicalName, Map<String, String> values);
}
