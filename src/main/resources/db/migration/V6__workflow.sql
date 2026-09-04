-- =========================================================
-- V6__workflow.sql: Workflow Engine Definitions & Instances
-- =========================================================

CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version_num INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_wf_defs_code ON workflow_definitions(code);

CREATE TABLE workflow_step_definitions (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    sequence_num INT NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_step_definition UNIQUE (definition_id, code)
);

CREATE INDEX idx_wf_steps_def_id ON workflow_step_definitions(definition_id);

CREATE TABLE workflow_instances (
    id UUID PRIMARY KEY,
    instance_type VARCHAR(50) NOT NULL,
    definition_id UUID NOT NULL REFERENCES workflow_definitions(id) ON DELETE RESTRICT,
    current_step_id UUID REFERENCES workflow_step_definitions(id) ON DELETE RESTRICT,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    application_id UUID REFERENCES internship_applications(id) ON DELETE CASCADE,
    internship_id UUID REFERENCES internships(id) ON DELETE CASCADE,
    finance_case_id UUID,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_wf_inst_def_id ON workflow_instances(definition_id);
CREATE INDEX idx_wf_inst_current_step_id ON workflow_instances(current_step_id);
CREATE INDEX idx_wf_inst_app_id ON workflow_instances(application_id);
CREATE INDEX idx_wf_inst_internship_id ON workflow_instances(internship_id);
CREATE INDEX idx_wf_inst_status ON workflow_instances(status);

CREATE TABLE workflow_actions (
    id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id UUID NOT NULL REFERENCES workflow_step_definitions(id) ON DELETE RESTRICT,
    performed_by_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    type VARCHAR(50) NOT NULL,
    decision VARCHAR(50),
    comment TEXT,
    sequence_number BIGINT NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_wf_actions_inst_id ON workflow_actions(instance_id);
CREATE INDEX idx_wf_actions_step_id ON workflow_actions(step_id);
CREATE INDEX idx_wf_actions_performed_by ON workflow_actions(performed_by_id);
CREATE INDEX idx_wf_actions_inst_seq ON workflow_actions(instance_id, sequence_number);
