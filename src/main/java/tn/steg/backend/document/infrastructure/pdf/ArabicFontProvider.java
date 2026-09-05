package tn.steg.backend.document.infrastructure.pdf;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import tn.steg.backend.common.domain.exception.BusinessRuleException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Centralized provider for the Noto Naskh Arabic font assets (Phase A11
 * hardening). All font paths live here — never hardcoded in templates or the
 * renderer.
 *
 * <pre>
 * assets/fonts/NotoNaskhArabic/
 * ├── NotoNaskhArabic-VariableFont_wght.ttf   (shipped, intentionally unused)
 * └── static/
 *     ├── NotoNaskhArabic-Regular.ttf   → regular Arabic text
 *     ├── NotoNaskhArabic-Medium.ttf    → intermediate weights (reserved)
 *     ├── NotoNaskhArabic-SemiBold.ttf  → intermediate weights (reserved)
 *     └── NotoNaskhArabic-Bold.ttf      → Arabic headings / labels
 * </pre>
 *
 * <p>Only the static files are used: variable fonts render unpredictably in
 * PDFBox and per-weight files give deterministic output. Fonts are validated
 * at startup (missing/unreadable file = clear configuration error, no silent
 * fallback to a font that cannot render Arabic).
 */
@Slf4j
@Component
public class ArabicFontProvider {

    static final String STATIC_BASE = "assets/fonts/NotoNaskhArabic/static/";
    static final String REGULAR_FILE = "NotoNaskhArabic-Regular.ttf";
    static final String MEDIUM_FILE = "NotoNaskhArabic-Medium.ttf";
    static final String SEMIBOLD_FILE = "NotoNaskhArabic-SemiBold.ttf";
    static final String BOLD_FILE = "NotoNaskhArabic-Bold.ttf";

    private final String basePath;

    private volatile byte[] regular;
    private volatile byte[] medium;
    private volatile byte[] semiBold;
    private volatile byte[] bold;

    public ArabicFontProvider() {
        this(STATIC_BASE);
    }

    /** Test hook: point at a bogus base to exercise missing-resource handling. */
    ArabicFontProvider(String basePath) {
        this.basePath = basePath;
    }

    @PostConstruct
    void validateFonts() {
        regular = load(REGULAR_FILE);
        medium = load(MEDIUM_FILE);
        semiBold = load(SEMIBOLD_FILE);
        bold = load(BOLD_FILE);
        log.info("Validated Arabic font assets (Noto Naskh Arabic static ×4)");
    }

    public byte[] regular() {
        return regular.clone();
    }

    public byte[] medium() {
        return medium.clone();
    }

    public byte[] semiBold() {
        return semiBold.clone();
    }

    public byte[] bold() {
        return bold.clone();
    }

    private byte[] load(String fileName) {
        String location = basePath + fileName;
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new BusinessRuleException("ARABIC_FONT_UNAVAILABLE",
                    "Arabic font asset is missing from the classpath at '" + location + "'. "
                            + "Restore src/main/resources/" + STATIC_BASE + " and rebuild; "
                            + "Arabic PDF generation is disabled rather than silently degraded.");
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = StreamUtils.copyToByteArray(in);
            if (bytes.length < 1024 || !isTrueType(bytes)) {
                throw new BusinessRuleException("ARABIC_FONT_UNAVAILABLE",
                        "Arabic font asset at '" + location + "' is unreadable or not a valid TrueType font.");
            }
            return bytes;
        } catch (IOException e) {
            throw new BusinessRuleException("ARABIC_FONT_UNAVAILABLE",
                    "Failed to read Arabic font asset at '" + location + "': " + e.getMessage());
        }
    }

    private boolean isTrueType(byte[] bytes) {
        if (bytes.length < 12) {
            return false;
        }
        long sfnt = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16)
                | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);
        // 0x00010000 (TrueType outlines) or 'true' — never accept bare CFF ('OTTO') here.
        return sfnt == 0x00010000L || sfnt == 0x74727565L;
    }
}
