-- User-backed supervision keeps one IAM identity for Admin and Supervisor users.
-- Legacy employee references remain available for historical organization data.

ALTER TABLE internship_assignments
    ADD COLUMN supervisor_user_id UUID REFERENCES users(id),
    ADD COLUMN assigned_by_user_id UUID REFERENCES users(id);

UPDATE internship_assignments assignment
SET supervisor_user_id = employee.user_id
FROM employees employee
WHERE assignment.supervisor_id = employee.id
  AND employee.user_id IS NOT NULL;

UPDATE internship_assignments assignment
SET assigned_by_user_id = employee.user_id
FROM employees employee
WHERE assignment.assigned_by_id = employee.id
  AND employee.user_id IS NOT NULL;

ALTER TABLE internships
    ADD COLUMN supervisor_user_id UUID REFERENCES users(id);

UPDATE internships internship
SET supervisor_user_id = assignment.supervisor_user_id
FROM internship_assignments assignment
WHERE assignment.internship_id = internship.id
  AND assignment.status = 'ACTIVE'
  AND assignment.supervisor_user_id IS NOT NULL;

CREATE INDEX idx_assignments_supervisor_user_id
    ON internship_assignments(supervisor_user_id);
CREATE INDEX idx_assignments_assigned_by_user_id
    ON internship_assignments(assigned_by_user_id);
CREATE INDEX idx_internships_supervisor_user_id
    ON internships(supervisor_user_id);