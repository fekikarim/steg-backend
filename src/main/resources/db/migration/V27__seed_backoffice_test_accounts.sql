-- V27__seed_backoffice_test_accounts.sql
-- Professional test accounts for Back Office QA (local/dev ONLY).
-- Provides 3 staff accounts covering the full platform scope:
--   SUPERVISOR, FINANCE, ADMIN. Passwords are BCrypt (cost 10).
-- Idempotent via ON CONFLICT DO NOTHING; safe to re-run.

-- ============================================================
-- Department for test employees (required FK)
-- ============================================================
INSERT INTO departments (id, code, name, description, active, parent_department_id, created_at, updated_at, version)
VALUES ('d0000000-0000-0000-0000-000000000001',
        'STEG-BO-QA',
        'STEG Back Office QA Department',
        'Test department for Back Office QA accounts (supervisor, finance, admin)',
        TRUE, NULL, now(), now(), 0)
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- Users (BCrypt cost 10, passwords documented in Back Office QA report)
--   supervisor.steg@steg.tn / Supervisor#2026
--   finance.steg@steg.tn    / Finance#2026
--   admin.steg@steg.tn      / Admin#2026
-- Hashes are $2a$10$ (BCrypt) — verified via bcrypt(10) generation.
-- ============================================================
INSERT INTO users (id, email, password_hash, status, enabled,
                   failed_login_attempts, locked_until, preferred_locale,
                   email_notifications_enabled,
                   created_at, updated_at, version)
VALUES
('c0000000-0000-0000-0000-000000000002',
 'supervisor.steg@steg.tn',
 '$2a$10$zf6v8/9Nq1YuT5ZPaB4nVuB7cEffV5ArOh4.xEijAJSL.uF0ZzAYq',
 'ACTIVE', TRUE, 0, NULL, 'fr', TRUE, now(), now(), 0),
('c0000000-0000-0000-0000-000000000003',
 'finance.steg@steg.tn',
 '$2a$10$qfsiaMccRl.iInvmvG4kCuuVY2r5dDLaLmYhU8P/v6Nqd78uW3Qee',
 'ACTIVE', TRUE, 0, NULL, 'fr', TRUE, now(), now(), 0),
('c0000000-0000-0000-0000-000000000004',
 'admin.steg@steg.tn',
 '$2a$10$6.0fQyG9rXXMZnvV/tBtr.9VggIw5ctaRDV3YuzXSzOrvQTXTlyF6',
 'ACTIVE', TRUE, 0, NULL, 'fr', TRUE, now(), now(), 0)
ON CONFLICT (email) DO NOTHING;

-- ============================================================
-- Role assignments (V15 role ids are deterministic)
--   SUPERVISOR -> b1000000-0000-0000-0000-000000000003
--   FINANCE    -> b1000000-0000-0000-0000-000000000005
--   ADMIN      -> b1000000-0000-0000-0000-000000000007
-- ============================================================
INSERT INTO user_roles (user_id, role_id) VALUES
('c0000000-0000-0000-0000-000000000002', 'b1000000-0000-0000-0000-000000000003'),
('c0000000-0000-0000-0000-000000000003', 'b1000000-0000-0000-0000-000000000005'),
('c0000000-0000-0000-0000-000000000004', 'b1000000-0000-0000-0000-000000000007')
ON CONFLICT DO NOTHING;

-- ============================================================
-- Employees linked to users (required for supervisor assignment,
-- finance approvals, and organization hierarchy display)
-- ============================================================
INSERT INTO employees (id, employee_number, first_name, last_name, phone_number,
                       position, hire_date, active, department_id, user_id,
                       created_at, updated_at, version)
VALUES
('e0000000-0000-0000-0000-000000000001',
 'EMP-SUP-001', 'Supervisor', 'STEG', '+216 71 000 001',
 'Supervisor', '2024-01-15', TRUE,
 'd0000000-0000-0000-0000-000000000001',
 'c0000000-0000-0000-0000-000000000002',
 now(), now(), 0),
('e0000000-0000-0000-0000-000000000002',
 'EMP-FIN-001', 'Finance', 'STEG', '+216 71 000 002',
 'Finance Manager', '2024-01-15', TRUE,
 'd0000000-0000-0000-0000-000000000001',
 'c0000000-0000-0000-0000-000000000003',
 now(), now(), 0),
('e0000000-0000-0000-0000-000000000003',
 'EMP-ADM-001', 'Admin', 'STEG', '+216 71 000 003',
 'Administrator', '2024-01-15', TRUE,
 'd0000000-0000-0000-0000-000000000001',
 'c0000000-0000-0000-0000-000000000004',
 now(), now(), 0)
ON CONFLICT (employee_number) DO NOTHING;
