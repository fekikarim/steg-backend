-- =========================================================
-- V50__certificate_issued_means_generated.sql (audit assumption #33)
-- Definition: a generated, non-revoked certificate IS "issued".
-- No code path ever wrote ISSUED (generation mints GENERATED, revoke mints
-- REVOKED), so the dashboard tile counting ISSUED was 0 by construction.
-- Collapse the dead state defensively: any ISSUED row becomes GENERATED.
-- Idempotent; expected to touch zero rows on real databases.
-- =========================================================

UPDATE certificates
SET status = 'GENERATED'
WHERE status = 'ISSUED';
