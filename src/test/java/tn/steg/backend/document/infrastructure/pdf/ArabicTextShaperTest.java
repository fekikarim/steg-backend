package tn.steg.backend.document.infrastructure.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production gate: Arabic shaping + RTL reordering without ICU4J.
 * Exact codepoint expectations pin the joining table, ligatures and Bidi flow.
 */
@DisplayName("ArabicTextShaper unit tests (offline shaping, no ICU4J)")
class ArabicTextShaperTest {

    private static String hex(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(String.format("%04X", cp));
        });
        return out.toString();
    }

    @Test
    @DisplayName("Assalam is shaped with medial seen and final lam-alef ligature")
    void shapesAssalam() {
        // ا ل س ل ا م → FE8D FEDF FEB4 FEFC FEE1
        assertThat(hex(ArabicTextShaper.shapeLogical("السلام")))
                .isEqualTo("FE8D FEDF FEB4 FEFC FEE1");
    }

    @Test
    @DisplayName("Standalone lam-alef uses the isolated ligature")
    void lamAlefIsolated() {
        assertThat(hex(ArabicTextShaper.shapeLogical("لا"))).isEqualTo("FEFB");
    }

    @Test
    @DisplayName("Right-joining letters break joining: darb stays isolated")
    void rightJoinersBreak() {
        // د ر ب → FEA9 FEAD FE8F
        assertThat(hex(ArabicTextShaper.shapeLogical("درب"))).isEqualTo("FEA9 FEAD FE8F");
    }

    @Test
    @DisplayName("Diacritics are preserved in place and stay transparent to joining")
    void diacriticsPreserved() {
        // مَرْحَبًا → FEE3 064E FEAE 0652 FEA3 064E FE92 064B FE8E
        String shaped = ArabicTextShaper.shapeLogical("مَرْحَبًا");
        assertThat(hex(shaped)).isEqualTo("FEE3 064E FEAE 0652 FEA3 064E FE92 064B FE8E");
    }

    @Test
    @DisplayName("Latin, digits and punctuation pass through shaping untouched")
    void latinPassthrough() {
        assertThat(ArabicTextShaper.shapeLogical("Stage 2026 (PFE): 150.00 TND"))
                .isEqualTo("Stage 2026 (PFE): 150.00 TND");
    }

    @Test
    @DisplayName("Pure Arabic reorders to visual (reversed) order")
    void pureArabicVisual() {
        String shaped = ArabicTextShaper.shapeLogical("مرحبا");
        assertThat(hex(shaped)).isEqualTo("FEE3 FEAE FEA3 FE92 FE8E");
        assertThat(hex(ArabicTextShaper.toVisual(shaped))).isEqualTo("FE8E FE92 FEA3 FEAE FEE3");
    }

    @Test
    @DisplayName("Pure LTR text is returned unchanged by visual reorder")
    void ltrPassthrough() {
        assertThat(ArabicTextShaper.toVisual("Hello 2026")).isEqualTo("Hello 2026");
    }

    @Test
    @DisplayName("Parentheses move and mirror around RTL runs (net effect: symmetric pair)")
    void parenthesesMirror() {
        // RTL base: L2 reverses the whole run (parens swap ends) and L4 mirrors
        // both bracket glyphs — net effect keeps '(' first and ')' last while the
        // Arabic between them reverses. Verified against java.awt.font.TextLayout.
        assertThat(hex(ArabicTextShaper.render("(مرحبا)")))
                .isEqualTo("0028 FE8E FE92 FEA3 FEAE FEE3 0029");
    }

    @Test
    @DisplayName("Mixed French/Arabic follows UBA run placement (trailing numbers lead RTL runs)")
    void mixedFrenchArabic() {
        String visual = ArabicTextShaper.render("Stage: مرحبا 2026");
        String reversedRun = new String(new int[]{0xFE8E, 0xFE92, 0xFEA3, 0xFEAE, 0xFEE3}, 0, 5);
        // UBA-conformant (verified against java.awt.font.TextLayout): the trailing
        // number run is placed before the RTL run in an LTR paragraph.
        assertThat(visual).startsWith("Stage: 2026 ");
        assertThat(visual).contains(reversedRun);
        assertThat(visual).endsWith("ﻣ");
    }

    @Test
    @DisplayName("Font selection covers presentation forms, diacritics and digits")
    void fontSelection() {
        assertThat(ArabicTextShaper.needsArabicFont(0xFE8D)).isTrue();
        assertThat(ArabicTextShaper.needsArabicFont(0x064E)).isTrue();
        assertThat(ArabicTextShaper.needsArabicFont(0x0660)).isTrue();
        assertThat(ArabicTextShaper.needsArabicFont(0x060C)).isTrue();
        assertThat(ArabicTextShaper.needsArabicFont('A')).isFalse();
        assertThat(ArabicTextShaper.needsArabicFont('é')).isFalse();
        assertThat(ArabicTextShaper.needsArabicFont(' ')).isFalse();
    }

    @Test
    @DisplayName("Null and empty inputs are safe")
    void nullSafe() {
        assertThat(ArabicTextShaper.shapeLogical(null)).isEqualTo("");
        assertThat(ArabicTextShaper.toVisual("")).isEqualTo("");
        assertThat(ArabicTextShaper.render(null)).isEqualTo("");
    }
}
