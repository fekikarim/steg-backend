package tn.steg.backend.iam.domain.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A valid account without a back-office role (ADMIN/SUPERVISOR) attempted the
 * staff-only login. Extends Spring's {@code AccessDeniedException} so the
 * default deny path still applies, but carries the stable machine-readable
 * code {@code BACK_OFFICE_DENIED} that the Angular login screen maps to its
 * "staff only" message (instead of the generic 403 envelope).
 */
public class BackOfficeAccessDeniedException extends AccessDeniedException {

    public static final String ERROR_CODE = "BACK_OFFICE_DENIED";

    public BackOfficeAccessDeniedException() {
        super("Back-office access requires an ADMIN or SUPERVISOR account.");
    }
}
