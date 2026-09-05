package tn.steg.backend.finance.domain.service;

import tn.steg.backend.document.domain.model.DocumentType;

import java.util.Set;

/**
 * Finance dossier composition policy (Phase A11). Pure: no Spring, no I/O.
 *
 * <p>A case may only reach READY_FOR_DECISION when every REQUIRED document type
 * is attached at least once with verification status VERIFIED. OPTIONAL types
 * never block the decision.
 */
public final class FinanceDossierPolicy {

    private static final Set<DocumentType> REQUIRED_TYPES = Set.of(
            DocumentType.CIN_COPY,
            DocumentType.INTERNSHIP_APPLICATION,
            DocumentType.ASSIGNMENT_LETTER,
            DocumentType.STEG_INTERNSHIP_REPORT
    );

    private static final Set<DocumentType> OPTIONAL_TYPES = Set.of(
            DocumentType.PROJECT_DEMO_IMAGE,
            DocumentType.CAHIER_DES_CHARGES,
            DocumentType.INTERNSHIP_CERTIFICATE
    );

    private FinanceDossierPolicy() {
    }

    public static Set<DocumentType> requiredTypes() {
        return REQUIRED_TYPES;
    }

    public static Set<DocumentType> optionalTypes() {
        return OPTIONAL_TYPES;
    }
}
