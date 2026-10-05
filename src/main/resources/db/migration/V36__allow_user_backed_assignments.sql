-- User-backed assignments may point directly to an Admin or Supervisor user.
-- Keep legacy employee references for historical assignments and compatibility.

ALTER TABLE internship_assignments
    ALTER COLUMN supervisor_id DROP NOT NULL,
    ALTER COLUMN assigned_by_id DROP NOT NULL;