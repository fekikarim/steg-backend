-- S10a — AI task drafts (AGENTS.md §7.4).
--
-- A draft is a server-side proposal produced by Gemini from a specifications
-- PDF (or typed manually when AI is unavailable). Drafts are NOT real tasks:
-- nothing is assigned and nobody is notified until the approved drafts are
-- bulk-added to one or more students, which creates real tasks atomically.
-- reference_internship_id anchors the internship period used to validate
-- AI-proposed due dates at generation time.

CREATE TABLE ai_task_drafts (
    id UUID PRIMARY KEY,
    created_by_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reference_internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    title VARCHAR(150) NOT NULL,
    description TEXT,
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_task_drafts_created_by_created
    ON ai_task_drafts (created_by_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_task_drafts_reference_internship
    ON ai_task_drafts (reference_internship_id);

COMMENT ON TABLE ai_task_drafts IS
    'S10a AI task drafts (AGENTS.md §7.4): server-side proposals, not real tasks until bulk-added.';
