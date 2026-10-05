-- S5 — Candidates workspace (AGENTS.md §5.1): soft delete for candidate profiles.
-- A deleted profile keeps its row so national_id_hash stays unique and existing
-- history (applications, audit) keeps its foreign keys, but it disappears from
-- every staff list and detail. The API refuses deletion while the candidate has
-- applications or internships (tasks, receipts and documents hang off those).
ALTER TABLE candidates ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_candidates_live_created_at
    ON candidates (created_at DESC)
    WHERE deleted_at IS NULL;
