-- =========================================================
-- V14__audit.sql: Audit Logging & Cross-Cutting Tracking
-- =========================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    old_values JSONB,
    new_values JSONB,
    ip_address VARCHAR(45),
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
-- Composite index for entity audit trail queries
CREATE INDEX idx_audit_logs_entity_history ON audit_logs(entity_type, entity_id, created_at);
