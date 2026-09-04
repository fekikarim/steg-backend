-- =========================================================
-- V9__document.sql: File Assets & Documents
-- =========================================================

CREATE TABLE file_assets (
    id UUID PRIMARY KEY,
    storage_provider VARCHAR(50) NOT NULL DEFAULT 'local',
    bucket VARCHAR(100),
    storage_key VARCHAR(500) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    encrypted_at_rest BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMPTZ NOT NULL,
    uploaded_by_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_file_assets_uploaded_by ON file_assets(uploaded_by_id);

-- Enforce foreign key for deliverable_versions
ALTER TABLE deliverable_versions
    ADD CONSTRAINT fk_deliv_versions_file
    FOREIGN KEY (file_asset_id) REFERENCES file_assets(id) ON DELETE RESTRICT;

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    restricted_access BOOLEAN NOT NULL DEFAULT FALSE,
    generated_automatically BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_documents_reference ON documents(reference);
CREATE INDEX idx_documents_type ON documents(type);

CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id) ON DELETE RESTRICT,
    version_number INT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_doc_versions_doc_id ON document_versions(document_id);
CREATE INDEX idx_doc_versions_file_id ON document_versions(file_asset_id);

CREATE TABLE application_documents (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES internship_applications(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
    verified_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    verification_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    verification_comment TEXT,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_app_docs_app_id ON application_documents(application_id);
CREATE INDEX idx_app_docs_doc_id ON application_documents(document_id);
CREATE INDEX idx_app_docs_status ON application_documents(verification_status);

CREATE TABLE internship_documents (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
    mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    generated_automatically BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_intern_docs_intern_id ON internship_documents(internship_id);
CREATE INDEX idx_intern_docs_doc_id ON internship_documents(document_id);
