-- =========================================================
-- V26__application_cin_uniqueness_and_tracking.sql
--
-- Front-office workflow correction:
--   1. Remove the obsolete proposed_theme field (project theme/topic).
--   2. Enforce "one and only one application per CIN" at the database level.
--      A Candidate is unique per national ID hash, so a unique constraint on
--      internship_applications.candidate_id guarantees one application per CIN.
--   3. Add an opaque tracking token (only its SHA-256 hex hash is stored) so a
--      candidate can track an anonymous submission without an account.
-- =========================================================

-- 1. Drop the removed field. Dropped before the uniqueness constraint so the
--    migration is idempotent-safe on fresh databases as well.
ALTER TABLE internship_applications
    DROP COLUMN IF EXISTS proposed_theme;

-- 2. Keep the most recent application per candidate and drop any older
--    duplicates so the unique constraint can be applied on existing data.
DELETE FROM internship_applications a
    USING internship_applications b
    WHERE a.candidate_id = b.candidate_id
      AND a.created_at < b.created_at;

DELETE FROM internship_applications a
    USING internship_applications b
    WHERE a.candidate_id = b.candidate_id
      AND a.created_at = b.created_at
      AND a.id < b.id;

ALTER TABLE internship_applications
    ADD CONSTRAINT uq_applications_candidate_id UNIQUE (candidate_id);

-- 3. Opaque tracking token: high-entropy raw token is returned to the
--    candidate once; only its SHA-256 hex digest is persisted here.
ALTER TABLE internship_applications
    ADD COLUMN tracking_token_hash VARCHAR(128);

CREATE UNIQUE INDEX idx_applications_tracking_token_hash
    ON internship_applications(tracking_token_hash);