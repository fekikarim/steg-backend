-- V34__remove_obsolete_roles.sql
-- Back Office is ADMIN + SUPERVISOR only. HR, FINANCE and DIRECTOR roles are
-- removed permanently: every holder is reassigned to ADMIN first (no orphan
-- user_roles rows), then role_permissions and the roles themselves are dropped.
-- Idempotent via code-based guards; safe to re-run.

-- 1. Reassign holders to ADMIN unless they already hold it (PK safety).
UPDATE user_roles
SET role_id = 'b1000000-0000-0000-0000-000000000007'
WHERE role_id IN (
    'b1000000-0000-0000-0000-000000000004',
    'b1000000-0000-0000-0000-000000000005',
    'b1000000-0000-0000-0000-000000000006'
)
AND NOT EXISTS (
    SELECT 1 FROM user_roles ur2
    WHERE ur2.user_id = user_roles.user_id
    AND ur2.role_id = 'b1000000-0000-0000-0000-000000000007'
);

-- 2. Drop any remaining obsolete assignments (holders already ADMIN).
DELETE FROM user_roles
WHERE role_id IN (
    'b1000000-0000-0000-0000-000000000004',
    'b1000000-0000-0000-0000-000000000005',
    'b1000000-0000-0000-0000-000000000006'
);

-- 3. Drop obsolete permission grants, then the roles (code-guarded).
DELETE FROM role_permissions
WHERE role_id IN (
    SELECT id FROM roles WHERE code IN ('HR', 'FINANCE', 'DIRECTOR')
);

DELETE FROM roles WHERE code IN ('HR', 'FINANCE', 'DIRECTOR');
