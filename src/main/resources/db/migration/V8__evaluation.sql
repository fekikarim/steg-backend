-- =========================================================
-- V8__evaluation.sql: Evaluation Templates, Evaluations & Comments
-- =========================================================

CREATE TABLE evaluation_templates (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version_num INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE evaluation_criteria (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES evaluation_templates(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    weight DECIMAL(5,2) NOT NULL DEFAULT 1.0,
    max_score DECIMAL(5,2) NOT NULL DEFAULT 20.0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_eval_criteria_template_id ON evaluation_criteria(template_id);

CREATE TABLE evaluations (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE RESTRICT,
    evaluator_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    template_id UUID REFERENCES evaluation_templates(id) ON DELETE RESTRICT,
    type VARCHAR(50) NOT NULL,
    evaluation_date DATE NOT NULL,
    feedback TEXT,
    total_score DECIMAL(5,2),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_evaluations_internship_id ON evaluations(internship_id);
CREATE INDEX idx_evaluations_evaluator_id ON evaluations(evaluator_id);
CREATE INDEX idx_evaluations_type ON evaluations(type);

CREATE TABLE evaluation_scores (
    id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(id) ON DELETE CASCADE,
    criterion_id UUID NOT NULL REFERENCES evaluation_criteria(id) ON DELETE RESTRICT,
    score DECIMAL(5,2) NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_eval_scores_eval_id ON evaluation_scores(evaluation_id);
CREATE INDEX idx_eval_scores_crit_id ON evaluation_scores(criterion_id);

CREATE TABLE evaluation_task_reviews (
    id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    score DECIMAL(5,2),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_eval_task_reviews_eval_id ON evaluation_task_reviews(evaluation_id);
CREATE INDEX idx_eval_task_reviews_task_id ON evaluation_task_reviews(task_id);

CREATE TABLE comments (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    journal_entry_id UUID REFERENCES journal_entries(id) ON DELETE CASCADE,
    deliverable_id UUID REFERENCES deliverables(id) ON DELETE CASCADE,
    evaluation_id UUID REFERENCES evaluations(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_comments_author_id ON comments(author_id);
CREATE INDEX idx_comments_journal_entry_id ON comments(journal_entry_id);
CREATE INDEX idx_comments_deliverable_id ON comments(deliverable_id);
CREATE INDEX idx_comments_evaluation_id ON comments(evaluation_id);
