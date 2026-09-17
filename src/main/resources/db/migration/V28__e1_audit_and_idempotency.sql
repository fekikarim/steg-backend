-- =========================================================
-- V28__e1_audit_and_idempotency.sql: E1.5 + E1.6 hardening
-- =========================================================
-- 1. Audit trail: actor role + correlation id on every record (E1.5).
--    Nullable for backward compatibility with pre-E1 rows.
-- 2. Idempotency keys for critical mutations (E1.6): application submit,
--    document upload, certificate generation, payment approval/receipt.

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS role_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_audit_logs_trace_id ON audit_logs(trace_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id UUID PRIMARY KEY,
    key_hash VARCHAR(128) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope VARCHAR(200) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_idempotency_key_user_scope UNIQUE (key_hash, user_id, scope)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_user_scope ON idempotency_keys(user_id, scope);
