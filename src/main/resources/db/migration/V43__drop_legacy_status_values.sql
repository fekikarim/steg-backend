-- S1b — legacy status removal (audit assumption #15).
--
-- The two application values were already migrated by V39 but the VALUES were
-- still live in the enums and in three clients; they are re-asserted here so the
-- mapping is stated in one place and the migration is idempotent.
UPDATE internship_applications
SET status = 'APPROVED'
WHERE status = 'ACCEPTED';

UPDATE internship_applications
SET status = 'MODIFICATION_REQUESTED'
WHERE status = 'NEEDS_CORRECTION';

-- The three internship values are migrated for the first time. V39 only touched
-- PLANNED rows whose application was already APPROVED, so PLANNED/ACTIVE/
-- COMPLETED rows were still reachable:
--   PLANNED   -> APPROVED      (created/approved, not started)
--   ACTIVE    -> IN_PROGRESS   (running)
--   COMPLETED -> VALIDATED     (finished and validated: still gates the
--                               certificate, the finance case and the logbook)
UPDATE internships
SET status = 'APPROVED'
WHERE status = 'PLANNED';

UPDATE internships
SET status = 'IN_PROGRESS'
WHERE status = 'ACTIVE';

UPDATE internships
SET status = 'VALIDATED'
WHERE status = 'COMPLETED';

-- The column DEFAULT was PLANNED in the entity; align it with the explicit
-- chain's first state so an inserted internship is never born legacy.
ALTER TABLE internships ALTER COLUMN status SET DEFAULT 'APPROVED';

-- NOTE on the workflow step definitions seeded by V16 ('PLANNED', 'ACTIVE',
-- 'COMPLETED'): those are workflow ACTION codes in workflow_step_definitions,
-- not internship statuses, and V16 is an applied migration that must not be
-- edited. They are therefore left in place, but the engine no longer writes a
-- legacy status for them — the internship-lifecycle steps now resolve onto the
-- explicit §4 states (PLANNED->APPROVED is the creation state, ACTIVE writes
-- IN_PROGRESS, COMPLETED writes VALIDATED), and the explicit lifecycle
-- (POST /api/internships/{id}/status-transitions) remains the authoritative
-- driver. No status value is left unmapped anywhere in this schema.
