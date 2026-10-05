-- S8 — certificate version history (AGENTS.md §5.7: editing data regenerates
-- the PDF and keeps history/audit).
--
-- A certificate keeps ONE stable reference per internship; every regeneration
-- appends a certificate_versions row (new PDF, bumped version number) so no
-- issued PDF is ever overwritten. Soft delete is the REVOKED status +
-- revoked_at (the row stays for the audit trail); generating while a
-- non-revoked certificate exists is refused, after a revocation it starts a
-- new row with a new reference.

ALTER TABLE certificates
    ADD COLUMN version_number INTEGER NOT NULL DEFAULT 1;

CREATE TABLE certificate_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    certificate_id UUID NOT NULL REFERENCES certificates (id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    file_asset_id UUID NOT NULL REFERENCES file_assets (id),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_certificate_version UNIQUE (certificate_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_certificate_versions_certificate
    ON certificate_versions (certificate_id, version_number DESC);

COMMENT ON TABLE certificate_versions IS
    'Issued certificate PDFs per regeneration (AGENTS.md §5.7). Append-only history.';

-- S8: race-free reference allocation. The old count-based scheme
-- (count + exists-check) collides under concurrent generation for distinct
-- internships; a sequence is atomic, so concurrent generations always mint
-- distinct references with no retry loop.
CREATE SEQUENCE IF NOT EXISTS certificate_reference_seq START 1;
