-- =========================================================
-- V13__ai.sql: AI Assistance & Recommendations
-- =========================================================

CREATE TABLE ai_analyses (
    id UUID PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    related_entity_type VARCHAR(100) NOT NULL,
    related_entity_id UUID NOT NULL,
    model_used VARCHAR(100),
    input_summary TEXT,
    output_summary TEXT,
    cin_excluded BOOLEAN NOT NULL DEFAULT TRUE,
    requested_by_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_ai_analyses_type ON ai_analyses(type);
CREATE INDEX idx_ai_analyses_related ON ai_analyses(related_entity_type, related_entity_id);
CREATE INDEX idx_ai_analyses_requested_by ON ai_analyses(requested_by_id);

CREATE TABLE ai_recommendations (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES ai_analyses(id) ON DELETE CASCADE,
    reviewed_by_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    recommendation_text TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PROPOSED',
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_ai_recom_analysis_id ON ai_recommendations(analysis_id);
CREATE INDEX idx_ai_recom_status ON ai_recommendations(status);
CREATE INDEX idx_ai_recom_reviewed_by ON ai_recommendations(reviewed_by_id);
