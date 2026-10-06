package tn.steg.backend.community.application.dto;

import jakarta.validation.constraints.Size;

/**
 * Payload for resolving a community report (T08 / D7, staff only).
 * Resolution is terminal; dismissal is recorded as a resolution text
 * (e.g. "no violation found").
 */
public record ResolveReportRequest(
        @Size(max = 500, message = "Resolution must not exceed 500 characters")
        String resolution
) {}
