package tn.steg.backend.document.infrastructure.pdf;

import java.text.Bidi;
import java.util.HashMap;
import java.util.Map;

/**
 * Arabic shaping + right-to-left visual reordering without external
 * dependencies (the project is offline; ICU4J is unavailable).
 *
 * <p>Pipeline per line, mirroring the standard ICU approach:
 * <ol>
 *   <li>{@link #shapeLogical(String)} — contextual joining in LOGICAL order
 *       (isolated/initial/medial/final via Presentation Forms-B), Lam-Alef
 *       ligatures, diacritics preserved transparently in place;</li>
 *   <li>{@link #toVisual(String)} — JDK {@link Bidi} reorder to visual order
 *       with mirroring (parentheses etc. inside RTL runs).</li>
 * </ol>
 * The result is drawn left-to-right with an Arabic font and stays selectable
 * and searchable (no text-to-image workaround).
 */
public final class ArabicTextShaper {

    private static final int NONE = -1;

    /** Joining behavior of a base character. */
    private enum Joining {
        /** Joins both sides (most letters). */
        DUAL,
        /** Joins to the previous character only (ا د ذ ر ز و ة ى …). */
        RIGHT,
        /** Never joins (hamza, digits, neutrals, tatweel handled separately). */
        NONE
    }

    /**
     * Base codepoint → {isolated, final, initial, medial} Presentation Forms-B.
     * {@code -1} where the form does not exist. Order verified against the
     * Unicode charts (FE80…FEF4 block sequence).
     */
    private static final Map<Integer, int[]> FORMS = new HashMap<>();

    /** Lam (0644) + Alef variant → {isolated ligature, final ligature}. */
    private static final Map<Integer, int[]> LAM_ALEF = Map.of(
            0x0627, new int[]{0xFEFB, 0xFEFC},
            0x0623, new int[]{0xFEF7, 0xFEF8},
            0x0625, new int[]{0xFEF9, 0xFEFA},
            0x0622, new int[]{0xFEF5, 0xFEF6});

    static {
        // {isolated, final, initial, medial}
        put(0x0621, 0xFE80, NONE, NONE, NONE);
        put(0x0622, 0xFE81, 0xFE82, NONE, NONE);
        put(0x0623, 0xFE83, 0xFE84, NONE, NONE);
        put(0x0624, 0xFE85, 0xFE86, NONE, NONE);
        put(0x0625, 0xFE87, 0xFE88, NONE, NONE);
        put(0x0626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C);
        put(0x0627, 0xFE8D, 0xFE8E, NONE, NONE);
        put(0x0628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92);
        put(0x0629, 0xFE93, 0xFE94, NONE, NONE);
        put(0x062A, 0xFE95, 0xFE96, 0xFE97, 0xFE98);
        put(0x062B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C);
        put(0x062C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0);
        put(0x062D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4);
        put(0x062E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8);
        put(0x062F, 0xFEA9, 0xFEAA, NONE, NONE);
        put(0x0630, 0xFEAB, 0xFEAC, NONE, NONE);
        put(0x0631, 0xFEAD, 0xFEAE, NONE, NONE);
        put(0x0632, 0xFEAF, 0xFEB0, NONE, NONE);
        put(0x0633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4);
        put(0x0634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8);
        put(0x0635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC);
        put(0x0636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0);
        put(0x0637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4);
        put(0x0638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8);
        put(0x0639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC);
        put(0x063A, 0xFECD, 0xFECE, 0xFECF, 0xFED0);
        put(0x0641, 0xFED1, 0xFED2, 0xFED3, 0xFED4);
        put(0x0642, 0xFED5, 0xFED6, 0xFED7, 0xFED8);
        put(0x0643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC);
        put(0x0644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0);
        put(0x0645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4);
        put(0x0646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8);
        put(0x0647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC);
        put(0x0648, 0xFEED, 0xFEEE, NONE, NONE);
        put(0x0649, 0xFEEF, 0xFEF0, NONE, NONE);
        put(0x064A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4);
        // Persian/Urdu letters common in names.
        put(0x067E, 0xFB56, 0xFB57, 0xFB58, 0xFB59);
        put(0x0686, 0xFB7A, 0xFB7B, 0xFB7C, 0xFB7D);
        put(0x0698, 0xFB8A, 0xFB8B, NONE, NONE);
        put(0x06A9, 0xFB8E, 0xFB8F, 0xFB90, 0xFB91);
        put(0x06AF, 0xFB92, 0xFB93, 0xFB94, 0xFB95);
        put(0x06BE, 0xFBAA, 0xFBAB, 0xFBAC, 0xFBAD);
        put(0x06CC, 0xFBFC, 0xFBFD, 0xFBFE, 0xFBFF);
    }

    private static void put(int base, int isolated, int fin, int init, int medial) {
        FORMS.put(base, new int[]{isolated, fin, init, medial});
    }

    private ArabicTextShaper() {
    }

    /**
     * Shapes one logical-order line: contextual forms + ligatures.
     * Non-Arabic characters pass through untouched.
     */
    public static String shapeLogical(String logical) {
        if (logical == null || logical.isEmpty()) {
            return logical == null ? "" : logical;
        }
        StringBuilder out = new StringBuilder(logical.length());
        boolean prevJoinsForward = false;
        int i = 0;
        int n = logical.length();
        while (i < n) {
            int cp = logical.codePointAt(i);
            int charCount = Character.charCount(cp);

            // Lam + Alef-variant ligature (consumes two codepoints).
            if (cp == 0x0644 && i + charCount < n) {
                int next = logical.codePointAt(i + charCount);
                int[] lig = LAM_ALEF.get(next);
                if (lig != null) {
                    out.appendCodePoint(prevJoinsForward ? lig[1] : lig[0]);
                    prevJoinsForward = false; // ligature behaves Alef-like forward
                    i += charCount + Character.charCount(next);
                    continue;
                }
            }

            if (isTransparent(cp)) {
                // Diacritics ride on the previous base: preserved in place,
                // invisible to joining on both sides.
                out.appendCodePoint(cp);
                i += charCount;
                continue;
            }

            int[] forms = FORMS.get(cp);
            if (forms == null) {
                if (cp == 0x0640) {
                    out.appendCodePoint(cp); // tatweel: joins, keeps its shape
                    prevJoinsForward = true;
                } else {
                    out.appendCodePoint(cp);
                    prevJoinsForward = false;
                }
                i += charCount;
                continue;
            }

            Joining joining = joiningOf(cp);
            int nextBase = nextBase(logical, i + charCount);
            boolean nextJoinsBackward = nextBase != NONE && canJoinBackward(nextBase);
            boolean joinsBackward = (joining == Joining.DUAL || joining == Joining.RIGHT) && prevJoinsForward;
            boolean joinsForward = joining == Joining.DUAL && nextJoinsBackward;

            int form;
            if (joinsBackward && joinsForward) {
                form = forms[3];
            } else if (joinsBackward) {
                form = forms[1];
            } else if (joinsForward) {
                form = forms[2];
            } else {
                form = forms[0];
            }
            out.appendCodePoint(form == NONE ? cp : form);
            prevJoinsForward = joining == Joining.DUAL;
            i += charCount;
        }
        return out.toString();
    }

    /**
     * Reorders shaped (or plain) text to visual order with mirroring.
     * Pure LTR input without RTL runs is returned unchanged.
     *
     * <p>Implemented on JDK {@link Bidi} (run levels +
     * {@link Bidi#reorderVisually} + explicit mirroring), since ICU4J is
     * unavailable offline.
     */
    public static String toVisual(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Bidi bidi = new Bidi(text, baseDirection(text));
        if (!bidi.isMixed() && !bidi.isRightToLeft()) {
            return text;
        }
        int len = text.length();
        Character[] chars = new Character[len];
        Byte[] levels = new Byte[len];
        for (int i = 0; i < len; i++) {
            chars[i] = text.charAt(i);
            levels[i] = (byte) bidi.getLevelAt(i);
        }
        Bidi.reorderVisually(toPrimitive(levels), 0, chars, 0, len);
        // Reorder the level markers in parallel so mirroring applies per visual position.
        Byte[] visualLevels = new Byte[len];
        for (int i = 0; i < len; i++) {
            visualLevels[i] = levels[i];
        }
        Bidi.reorderVisually(toPrimitive(levels), 0, visualLevels, 0, len);
        StringBuilder visual = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = chars[i];
            visual.append((visualLevels[i] & 1) == 1 ? mirror(c) : c);
        }
        return visual.toString();
    }

    /**
     * Paragraph base direction from the first strong character (explicit,
     * instead of relying on the JDK DEFAULT flag semantics).
     */
    static int baseDirection(String text) {
        int len = text.length();
        for (int i = 0; i < len; ) {
            int cp = text.codePointAt(i);
            byte dir = Character.getDirectionality(cp);
            if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                    || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                    || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
                return Bidi.DIRECTION_LEFT_TO_RIGHT;
            }
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                return Bidi.DIRECTION_RIGHT_TO_LEFT;
            }
            i += Character.charCount(cp);
        }
        return Bidi.DIRECTION_LEFT_TO_RIGHT;
    }

    private static byte[] toPrimitive(Byte[] boxed) {
        byte[] out = new byte[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            out[i] = boxed[i];
        }
        return out;
    }

    /** Mirrored counterpart for brackets/quotes in RTL runs (else identity). */
    static char mirror(char c) {
        return switch (c) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            case '<' -> '>';
            case '>' -> '<';
            case '«' -> '»';
            case '»' -> '«';
            case '‹' -> '›';
            case '›' -> '‹';
            default -> c;
        };
    }

    /** Full pipeline for one logical line: shape, then reorder to visual. */
    public static String render(String logicalLine) {
        return toVisual(shapeLogical(logicalLine));
    }

    /**
     * Whether the character needs the Arabic font (Arabic blocks, presentation
     * forms, diacritics, tatweel, Arabic-Indic digits and punctuation).
     */
    public static boolean needsArabicFont(int codePoint) {
        return (codePoint >= 0x0600 && codePoint <= 0x06FF)
                || (codePoint >= 0x0750 && codePoint <= 0x077F)
                || (codePoint >= 0xFB50 && codePoint <= 0xFDFF)
                || (codePoint >= 0xFE70 && codePoint <= 0xFEFF);
    }

    // ------------------------------------------------------------------
    // Joining model
    // ------------------------------------------------------------------

    private static Joining joiningOf(int cp) {
        // Right-joining only.
        switch (cp) {
            case 0x0622, 0x0623, 0x0624, 0x0625, 0x0627, 0x0629,
                    0x062F, 0x0630, 0x0631, 0x0632, 0x0648, 0x0649, 0x0698:
                return Joining.RIGHT;
            default:
                break;
        }
        if (FORMS.containsKey(cp)) {
            return Joining.DUAL;
        }
        return Joining.NONE;
    }

    private static boolean canJoinBackward(int baseOrPair) {
        if (baseOrPair == NONE) {
            return false;
        }
        if (baseOrPair == 0x0640) {
            return true;
        }
        Joining joining = joiningOf(baseOrPair);
        return joining == Joining.DUAL || joining == Joining.RIGHT;
    }

    /** Next base unit skipping transparent diacritics; Lam+Alef pairs count as one Alef-like unit. */
    private static int nextBase(String s, int from) {
        int i = from;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isTransparent(cp)) {
                return cp;
            }
            i += Character.charCount(cp);
        }
        return NONE;
    }

    /** Tashkeel and friends: transparent to joining, preserved in place. */
    private static boolean isTransparent(int cp) {
        return (cp >= 0x064B && cp <= 0x0655) || cp == 0x0670;
    }
}
