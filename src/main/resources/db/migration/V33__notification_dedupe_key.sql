-- =========================================================
-- V33__notification_dedupe_key.sql: exactly-once submission emails
-- - Optional idempotency key on notifications (e.g. APP_SUBMITTED:<appId>).
--   The submission listener reuses the existing row instead of creating a
--   duplicate notification + duplicate confirmation email on retries,
--   refreshes, duplicate clicks, timeout recovery, or event reprocessing.
-- - Partial-style semantics via plain UNIQUE: PostgreSQL treats NULLs as
--   distinct, so all pre-existing / non-deduped notifications (dedupe_key
--   IS NULL) are unaffected; only equal non-null keys collide.
-- =========================================================

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_dedupe_key
    ON notifications(dedupe_key);
