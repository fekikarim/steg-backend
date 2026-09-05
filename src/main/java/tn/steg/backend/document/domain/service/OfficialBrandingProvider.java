package tn.steg.backend.document.domain.service;

/**
 * Port for official STEG branding (Phase A11).
 *
 * <p>The logo is loaded through the classpath (never an absolute filesystem
 * path, never the frontend, never the database) and shared by certificates
 * and payment receipts. If the resource is missing, generation fails fast
 * with a clear server-side configuration error instead of silently producing
 * an official document without the logo.
 */
public interface OfficialBrandingProvider {

    /**
     * @return a copy of the official logo PNG bytes (defensive copy)
     * @throws tn.steg.backend.common.domain.exception.BusinessRuleException
     *         with code BRANDING_UNAVAILABLE when the resource is missing
     */
    byte[] getLogoPngBytes();
}
