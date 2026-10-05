-- S7 — internship validation runs + manual per-document decisions
-- (AGENTS.md §5.11 steps 3-4, §7.3).
--
-- The python-ai document verification is ADVISORY ONLY: every run is persisted
-- (who ran it, when, which document version, the full check-by-check result
-- JSON) and the Admin's MANUAL decision per document is what moves the
-- internship (REPORT_SUBMITTED -> UNDER_VALIDATION on the first decision,
-- UNDER_VALIDATION -> VALIDATED only when BOTH documents are VALIDATED, back
-- to REPORT_SUBMITTED on REJECTED for resubmission — audit assumption #18).
-- The AI result itself never changes any status.

CREATE TABLE validation_verification_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    internship_id UUID NOT NULL REFERENCES internships (id) ON DELETE CASCADE,
    document_type VARCHAR(20) NOT NULL,
    deliverable_id UUID,
    file_asset_id UUID,
    document_version INTEGER,
    run_by UUID REFERENCES users (id) ON DELETE SET NULL,
    run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    overall VARCHAR(20) NOT NULL,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_verification_run_document CHECK (document_type IN ('REPORT', 'JOURNAL')),
    CONSTRAINT chk_verification_run_overall CHECK (overall IN ('PASS', 'FAIL', 'INCONCLUSIVE'))
);

CREATE INDEX IF NOT EXISTS idx_verification_runs_internship
    ON validation_verification_runs (internship_id, run_at DESC);

COMMENT ON TABLE validation_verification_runs IS
    'Advisory python-ai verification runs (AGENTS.md §7.3). Never drives a status change by itself.';

CREATE TABLE validation_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    internship_id UUID NOT NULL REFERENCES internships (id) ON DELETE CASCADE,
    document_type VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    comment TEXT,
    decided_by UUID REFERENCES users (id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_validation_decision_document CHECK (document_type IN ('REPORT', 'JOURNAL')),
    CONSTRAINT chk_validation_decision_value CHECK (decision IN ('VALIDATED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_validation_decisions_internship
    ON validation_decisions (internship_id, document_type, decided_at DESC);

COMMENT ON TABLE validation_decisions IS
    'Mandatory manual per-document Admin decisions (AGENTS.md §5.11 step 4). '
    'Immutable history: the current decision per document is the latest row.';
