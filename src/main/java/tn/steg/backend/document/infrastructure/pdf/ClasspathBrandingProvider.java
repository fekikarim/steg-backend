package tn.steg.backend.document.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.document.domain.service.OfficialBrandingProvider;

import java.io.IOException;
import java.io.InputStream;

/**
 * Classpath implementation of {@link OfficialBrandingProvider}.
 */
@Slf4j
@Component
public class ClasspathBrandingProvider implements OfficialBrandingProvider {

    static final String LOGO_CLASSPATH_LOCATION = "assets/logo/logo-steg-1200x327.png";

    private volatile byte[] cachedLogo;

    @Override
    public byte[] getLogoPngBytes() {
        byte[] logo = cachedLogo;
        if (logo == null) {
            synchronized (this) {
                logo = cachedLogo;
                if (logo == null) {
                    logo = loadLogo();
                    cachedLogo = logo;
                }
            }
        }
        return logo.clone();
    }

    private byte[] loadLogo() {
        ClassPathResource resource = new ClassPathResource(LOGO_CLASSPATH_LOCATION);
        if (!resource.exists()) {
            throw new BusinessRuleException("BRANDING_UNAVAILABLE",
                    "Official STEG logo is missing from the classpath at '"
                            + LOGO_CLASSPATH_LOCATION + "'. Place logo-steg-1200x327.png under "
                            + "src/main/resources/assets/logo/ and rebuild.");
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = StreamUtils.copyToByteArray(in);
            if (bytes.length == 0) {
                throw new BusinessRuleException("BRANDING_UNAVAILABLE",
                        "Official STEG logo at '" + LOGO_CLASSPATH_LOCATION + "' is empty.");
            }
            log.info("Loaded official STEG branding logo ({} bytes)", bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new BusinessRuleException("BRANDING_UNAVAILABLE",
                    "Failed to read the official STEG logo: " + e.getMessage());
        }
    }
}
