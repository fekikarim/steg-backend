-- =========================================================
-- V23__certificate_single_active.sql: Phase A11 hardening
-- At most one non-revoked certificate per internship: repeated generation
-- requests get an explicit 409 (existing reference) instead of silent
-- duplicates, including under concurrent requests (backstop for the
-- service-level check).
-- =========================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_certificate_per_internship
    ON certificates(internship_id)
    WHERE status IN ('GENERATED', 'ISSUED');
