-- T03 — Student-defined task categories (ST-TASK-03/04/05, D5/D5b).
--
-- A category belongs to exactly ONE student (owner_user_id): it is the
-- student's private organisation tool and is never exposed to supervisors
-- or admins (no staff endpoint or DTO reads these tables).
--
-- Classification itself is a nullable task -> category link: assigning or
-- clearing it never touches tasks.status (BR-19). Deleting a category sets
-- its tasks back to unclassified (ON DELETE SET NULL) — tasks are never
-- deleted with their category.
--
-- Apply batches persist every accepted AI/manual batch with the previous
-- value per task so an undo can restore exactly those tasks, and only the
-- ones the student has not changed since (compare-and-set).

CREATE TABLE task_categories (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    color VARCHAR(16),
    position INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Names are unique per student, case-insensitively (server also checks).
CREATE UNIQUE INDEX uq_task_categories_owner_lower_name
    ON task_categories (owner_user_id, LOWER(name));

CREATE INDEX idx_task_categories_owner_position
    ON task_categories (owner_user_id, position, created_at);

COMMENT ON TABLE task_categories IS
    'T03 student-defined task categories (ST-TASK-03): private per student, classification is independent of task status.';

ALTER TABLE tasks
    ADD COLUMN task_category_id UUID REFERENCES task_categories(id) ON DELETE SET NULL;

CREATE INDEX idx_tasks_task_category_id ON tasks(task_category_id);

COMMENT ON COLUMN tasks.task_category_id IS
    'T03 personal classification link (ST-TASK-05): nullable, never changes tasks.status.';

CREATE TABLE task_category_apply_batches (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_task_category_apply_batches_user_internship
    ON task_category_apply_batches (user_id, internship_id, created_at DESC);

CREATE TABLE task_category_apply_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES task_category_apply_batches(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    previous_category_id UUID REFERENCES task_categories(id) ON DELETE SET NULL,
    applied_category_id UUID REFERENCES task_categories(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_task_category_apply_items_batch
    ON task_category_apply_items (batch_id);

COMMENT ON TABLE task_category_apply_batches IS
    'T03 accepted classification batches (ST-TASK-04): per-item previous values make undo a compare-and-set restore.';
