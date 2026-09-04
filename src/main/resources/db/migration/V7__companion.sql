-- =========================================================
-- V7__companion.sql: Internship Journal, Tasks & Deliverables
-- =========================================================

CREATE TABLE internship_journals (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_journals_internship_id ON internship_journals(internship_id);

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES internship_journals(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    validated_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    entry_date DATE NOT NULL,
    submitted_at TIMESTAMPTZ,
    validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_journal_entries_journal_id ON journal_entries(journal_id);
CREATE INDEX idx_journal_entries_author_id ON journal_entries(author_id);
CREATE INDEX idx_journal_entries_status ON journal_entries(status);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    created_by_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_to_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'TODO',
    due_date DATE,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_tasks_internship_id ON tasks(internship_id);
CREATE INDEX idx_tasks_created_by ON tasks(created_by_id);
CREATE INDEX idx_tasks_assigned_to ON tasks(assigned_to_id);
CREATE INDEX idx_tasks_status ON tasks(status);

CREATE TABLE deliverables (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    validated_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    current_version INT NOT NULL DEFAULT 1,
    submitted_at TIMESTAMPTZ,
    validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_deliverables_internship_id ON deliverables(internship_id);
CREATE INDEX idx_deliverables_status ON deliverables(status);

CREATE TABLE deliverable_versions (
    id UUID PRIMARY KEY,
    deliverable_id UUID NOT NULL REFERENCES deliverables(id) ON DELETE CASCADE,
    file_asset_id UUID NOT NULL,
    uploaded_by_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    version_number INT NOT NULL,
    change_summary TEXT,
    uploaded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_deliv_versions_deliv_id ON deliverable_versions(deliverable_id);
CREATE INDEX idx_deliv_versions_file_id ON deliverable_versions(file_asset_id);
