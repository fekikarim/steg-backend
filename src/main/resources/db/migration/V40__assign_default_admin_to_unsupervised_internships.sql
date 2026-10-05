-- V40__assign_default_admin_to_unsupervised_internships.sql
-- Invariant: an internship must never exist without a supervisor.
-- Assign default admin to any existing internships that lack a supervisor.

DO $$
DECLARE
    default_admin_id UUID;
    default_dept_id UUID;
BEGIN
    SELECT u.id INTO default_admin_id
    FROM users u
    JOIN user_roles ur ON ur.user_id = u.id
    JOIN roles r ON r.id = ur.role_id
    WHERE r.code = 'ADMIN'
    ORDER BY u.created_at ASC
    LIMIT 1;

    IF default_admin_id IS NULL THEN
        default_admin_id := 'c0000000-0000-0000-0000-000000000004';
    END IF;

    SELECT id INTO default_dept_id
    FROM departments
    ORDER BY created_at ASC
    LIMIT 1;

    -- Update internships without supervisor_user_id
    UPDATE internships
    SET supervisor_user_id = default_admin_id
    WHERE supervisor_user_id IS NULL;

    -- Ensure each internship also has an ACTIVE assignment
    INSERT INTO internship_assignments (
        id, internship_id, department_id, supervisor_user_id, assigned_by_user_id,
        assigned_at, start_date, end_date, status, created_at, updated_at, version
    )
    SELECT
        gen_random_uuid(),
        i.id,
        COALESCE(default_dept_id, 'd0000000-0000-0000-0000-000000000001'),
        default_admin_id,
        default_admin_id,
        CURRENT_DATE,
        i.start_date,
        i.end_date,
        'ACTIVE',
        now(),
        now(),
        0
    FROM internships i
    WHERE NOT EXISTS (
        SELECT 1 FROM internship_assignments a
        WHERE a.internship_id = i.id AND a.status = 'ACTIVE'
    );

END $$;
