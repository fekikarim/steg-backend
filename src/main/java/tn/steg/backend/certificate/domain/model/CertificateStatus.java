package tn.steg.backend.certificate.domain.model;

/**
 * Certificate lifecycle (audit assumption #33): a generated, non-revoked
 * certificate IS "issued". The dead ISSUED state was removed (V50 remaps
 * any such row to GENERATED); revoke is soft (REVOKED, row kept for audit).
 */
public enum CertificateStatus {
    GENERATED,
    REVOKED
}
