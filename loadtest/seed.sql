-- Phase A14 — load-test seed (local/demo ONLY, never production).
--
-- Creates a staff ADMIN user for the k6 baseline script. Password is
-- 'Loadtest#123' (BCrypt, cost 10). Rotate or drop this user after measuring.
-- Idempotent: safe to run multiple times (ON CONFLICT DO NOTHING).
--
-- Usage: docker compose exec postgres psql -U steg -d stegdb -f /seed.sql
-- (see ../../docker-compose.yml for the exact service/database names).

INSERT INTO users (id, email, password_hash, status, enabled,
                   failed_login_attempts, locked_until, preferred_locale,
                   created_at, updated_at, version)
VALUES ('c0000000-0000-0000-0000-000000000001',
        'load.admin@steg.tn',
        '$2a$10$anWITYWxMcIV.P6ZneC3ROPai9pYDsU1xIskZ4QWJkOWCrGjVLpL6',
        'ACTIVE', TRUE, 0, NULL, 'fr', now(), now(), 0)
ON CONFLICT (email) DO NOTHING;

-- ADMIN role id is seeded deterministically by V15__seed_rbac.sql.
INSERT INTO user_roles (user_id, role_id)
VALUES ('c0000000-0000-0000-0000-000000000001',
        'b1000000-0000-0000-0000-000000000007')
ON CONFLICT DO NOTHING;

-- Demo university so the k6 journey can complete a candidate profile
-- (profile creation requires a universityId; none is seeded by migrations).
INSERT INTO universities (id, code, name, active, created_at, updated_at, version)
VALUES ('c0000000-0000-0000-0000-000000000002',
        'LOAD-UNI', 'Load Test University', TRUE, now(), now(), 0)
ON CONFLICT (code) DO NOTHING;
