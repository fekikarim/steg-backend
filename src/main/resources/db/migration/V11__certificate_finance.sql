-- =========================================================
-- V11__certificate_finance.sql: Certificates & Financial Management
-- =========================================================

CREATE TABLE certificates (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'GENERATED',
    template_code VARCHAR(50) NOT NULL,
    template_version INT NOT NULL DEFAULT 1,
    generated_at TIMESTAMPTZ NOT NULL,
    issue_date DATE NOT NULL,
    issued_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE RESTRICT,
    generated_by_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_certificates_reference ON certificates(reference);
CREATE INDEX idx_certificates_internship_id ON certificates(internship_id);
CREATE INDEX idx_certificates_status ON certificates(status);

CREATE TABLE finance_cases (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'OPENED',
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE RESTRICT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_finance_cases_reference ON finance_cases(reference);
CREATE INDEX idx_finance_cases_internship_id ON finance_cases(internship_id);
CREATE INDEX idx_finance_cases_status ON finance_cases(status);

-- Foreign key link from workflow_instances to finance_cases
ALTER TABLE workflow_instances
    ADD CONSTRAINT fk_wf_inst_finance
    FOREIGN KEY (finance_case_id) REFERENCES finance_cases(id) ON DELETE CASCADE;

CREATE TABLE finance_case_documents (
    id UUID PRIMARY KEY,
    finance_case_id UUID NOT NULL REFERENCES finance_cases(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
    reviewed_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    verification_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    verification_comment TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_fc_docs_case_id ON finance_case_documents(finance_case_id);
CREATE INDEX idx_fc_docs_doc_id ON finance_case_documents(document_id);

CREATE TABLE payment_calculations (
    id UUID PRIMARY KEY,
    finance_case_id UUID NOT NULL REFERENCES finance_cases(id) ON DELETE CASCADE UNIQUE,
    completed_months INT NOT NULL,
    payable_months INT NOT NULL,
    rate_per_month DECIMAL(10,2) NOT NULL,
    calculated_amount DECIMAL(10,2) NOT NULL,
    capped_amount DECIMAL(10,2) NOT NULL,
    cap_applied BOOLEAN NOT NULL DEFAULT FALSE,
    currency_code VARCHAR(10) NOT NULL DEFAULT 'TND',
    calculated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_calcs_case_id ON payment_calculations(finance_case_id);

CREATE TABLE payment_approvals (
    id UUID PRIMARY KEY,
    finance_case_id UUID NOT NULL REFERENCES finance_cases(id) ON DELETE CASCADE,
    decided_by_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    decision VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    comment TEXT,
    decision_sequence INT NOT NULL DEFAULT 1,
    decided_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_approvals_case_id ON payment_approvals(finance_case_id);
CREATE INDEX idx_payment_approvals_decided_by ON payment_approvals(decided_by_id);

CREATE TABLE payment_receipts (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    finance_case_id UUID NOT NULL REFERENCES finance_cases(id) ON DELETE RESTRICT UNIQUE,
    issued_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id) ON DELETE RESTRICT,
    status VARCHAR(50) NOT NULL DEFAULT 'GENERATED',
    amount DECIMAL(10,2) NOT NULL,
    paid_months INT NOT NULL,
    currency_code VARCHAR(10) NOT NULL DEFAULT 'TND',
    payment_date DATE,
    issued_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_receipts_reference ON payment_receipts(reference);
CREATE INDEX idx_payment_receipts_case_id ON payment_receipts(finance_case_id);
