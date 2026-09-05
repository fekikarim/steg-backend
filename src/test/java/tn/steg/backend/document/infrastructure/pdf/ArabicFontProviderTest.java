package tn.steg.backend.document.infrastructure.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.common.domain.exception.BusinessRuleException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production gate: Arabic font assets load from the classpath; a missing or
 * corrupt asset fails fast instead of silently degrading Arabic rendering.
 */
@DisplayName("ArabicFontProvider tests (classpath assets, fail-fast)")
class ArabicFontProviderTest {

    @Test
    @DisplayName("All four static fonts load from the classpath and look like TrueType")
    void loadsAllStaticFonts() {
        ArabicFontProvider provider = new ArabicFontProvider();
        provider.validateFonts();

        for (byte[] font : new byte[][]{
                provider.regular(), provider.medium(), provider.semiBold(), provider.bold()}) {
            assertThat(font.length).isGreaterThan(1024);
            long sfnt = ((font[0] & 0xFFL) << 24) | ((font[1] & 0xFFL) << 16)
                    | ((font[2] & 0xFFL) << 8) | (font[3] & 0xFFL);
            assertThat(sfnt).isIn(0x00010000L, 0x74727565L);
        }
        // Defensive copies: callers cannot mutate the cache.
        assertThat(provider.regular()).isNotSameAs(provider.regular());
        assertThat(provider.regular()).isEqualTo(provider.regular());
    }

    @Test
    @DisplayName("Missing font base fails fast with a clear configuration error")
    void missingBaseFailsFast() {
        ArabicFontProvider provider = new ArabicFontProvider("assets/fonts/DOES-NOT-EXIST/");
        assertThatThrownBy(provider::validateFonts)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("assets/fonts/DOES-NOT-EXIST/");
    }
}
