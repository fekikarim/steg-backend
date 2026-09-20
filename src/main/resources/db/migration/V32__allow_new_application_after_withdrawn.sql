-- V32: Allow new application after WITHDRAWN
-- Previously, uq_applications_candidate_id prevented any second application per candidate,
-- even after withdrawal. Now, allow a new application if the previous one is WITHDRAWN.
-- We replace the table constraint with a partial unique index that only enforces uniqueness
-- for active (non-WITHDRAWN) applications.

ALTER TABLE internship_applications DROP CONSTRAINT IF EXISTS uq_applications_candidate_id;

DROP INDEX IF EXISTS uq_applications_candidate_id;
DROP INDEX IF EXISTS uq_applications_candidate_id_active;

CREATE UNIQUE INDEX uq_applications_candidate_id_active
    ON internship_applications(candidate_id)
    WHERE status != 'WITHDRAWN';
