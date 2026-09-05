-- =========================================================
-- V21__notification_delivery_retry.sql: Phase A10 hardening
-- - Bounded retry bookkeeping on notification_deliveries
--   (attempt_count + next_retry_at; failures never silently dropped)
-- - Per-user EMAIL opt-in (default TRUE; EMAIL channel additionally
--   requires steg.notifications.mail.enabled=true)
-- =========================================================

ALTER TABLE notification_deliveries
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;

ALTER TABLE notification_deliveries
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_notif_deliv_retry
    ON notification_deliveries(status, next_retry_at);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;
