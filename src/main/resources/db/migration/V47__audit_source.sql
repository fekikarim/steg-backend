-- S9 — audit source channel (AGENTS.md §8.2).
--
-- Every audit row records WHERE the action came from: FRONT_OFFICE (public
-- candidate intake), BACK_OFFICE (staff screens), MOBILE (STEG intern app),
-- SYSTEM (schedulers, dead letters) or AI (verification runs). Pre-existing
-- rows predate the channel and are honestly labeled BACK_OFFICE — every
-- writer at the time was a staff/Admin flow.

ALTER TABLE audit_logs
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'BACK_OFFICE';

CREATE INDEX IF NOT EXISTS idx_audit_logs_source_created
    ON audit_logs (source, created_at DESC);

COMMENT ON COLUMN audit_logs.source IS
    'Origin channel of the audited action (AGENTS.md §8.2): FRONT_OFFICE | BACK_OFFICE | MOBILE | SYSTEM | AI.';
