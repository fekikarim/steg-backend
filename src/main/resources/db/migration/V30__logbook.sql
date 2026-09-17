-- =========================================================
-- V30__logbook.sql: E3 official internship logbook lifecycle
-- DRAFT → SUBMITTED → VALIDATED/REJECTED → (OFFICIAL)
-- The logbook is human-reviewed text submitted by the intern and
-- validated by the assigned supervisor; AI draft is never official.
-- =========================================================

CREATE TABLE internship_logbooks (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    ai_analysis_id UUID,
    draft_text TEXT,
    final_text TEXT,
    submitted_at TIMESTAMPTZ,
    validated_at TIMESTAMPTZ,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE RESTRICT,
    submitted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    validated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- One official logbook line per internship.
CREATE UNIQUE INDEX IF NOT EXISTS uq_logbook_internship
    ON internship_logbooks(internship_id);

CREATE INDEX idx_logbooks_status ON internship_logbooks(status);
CREATE INDEX idx_logbooks_submitted_by ON internship_logbooks(submitted_by);