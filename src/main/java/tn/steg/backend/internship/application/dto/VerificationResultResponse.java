package tn.steg.backend.internship.application.dto;

import java.util.List;

/** S7 AI verification result as returned to the Admin (advisory only, §7.3). */
public record VerificationResultResponse(
        String documentType,
        String overall,
        boolean degraded,
        List<ValidationDetailResponse.CheckView> checks,
        String runId
) {
}
