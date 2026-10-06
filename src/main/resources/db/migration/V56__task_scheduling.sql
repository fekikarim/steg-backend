-- T04 / D8 — scheduled task visibility (SU-TASK-04).
--
-- A task may carry the moment from which the student is allowed to see it.
-- NULL (the default, incl. every pre-V56 row) means immediate: nothing
-- changes for existing tasks. A future instant hides the task from the
-- student in every student-facing read until it passes; supervisors and
-- admins always see it (with the instant exposed for the "scheduled" badge).
-- A scheduler notifies the student exactly once when the moment passes
-- (dedupe key SCHEDULED_TASK_VISIBLE:<taskId>).

ALTER TABLE tasks
    ADD COLUMN visible_from TIMESTAMPTZ;

CREATE INDEX idx_tasks_visible_from ON tasks(visible_from);

COMMENT ON COLUMN tasks.visible_from IS
    'T04/D8 scheduled visibility (SU-TASK-04): NULL = visible immediately; a future instant hides the task from the student until it passes. Compared against now() — TIMESTAMPTZ is time-zone safe.';
