-- ==============================================================================
-- V16__seed_workflow_definitions.sql
-- Seed WorkflowDefinitions and WorkflowStepDefinitions for the 3 core processes:
-- 1. application-review (Application review lifecycle)
-- 2. internship-lifecycle (Internship assignment and execution lifecycle)
-- 3. payment-approval (Finance case validation and payment approval)
-- ==============================================================================

-- 1. application-review
INSERT INTO workflow_definitions (id, code, name, description, active, version_num, created_at, updated_at, version)
VALUES (
    '00000001-0000-0000-0000-000000000001',
    'application-review',
    'Application Review Process',
    'Governs candidate internship application review, correction, acceptance, and rejection.',
    TRUE,
    1,
    now(),
    now(),
    0
);

INSERT INTO workflow_step_definitions (id, definition_id, code, name, sequence_num, required, created_at, updated_at, version)
VALUES
('00000002-0000-0000-0000-000000000001', '00000001-0000-0000-0000-000000000001', 'SUBMITTED', 'Application Submitted', 1, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000002', '00000001-0000-0000-0000-000000000001', 'UNDER_REVIEW', 'HR & Department Review', 2, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000003', '00000001-0000-0000-0000-000000000001', 'FINAL_DECISION', 'Final Decision (Accept/Reject)', 3, TRUE, now(), now(), 0);

-- 2. internship-lifecycle
INSERT INTO workflow_definitions (id, code, name, description, active, version_num, created_at, updated_at, version)
VALUES (
    '00000001-0000-0000-0000-000000000002',
    'internship-lifecycle',
    'Internship Execution Lifecycle',
    'Governs internship assignment, activation, progress, and completion.',
    TRUE,
    1,
    now(),
    now(),
    0
);

INSERT INTO workflow_step_definitions (id, definition_id, code, name, sequence_num, required, created_at, updated_at, version)
VALUES
('00000002-0000-0000-0000-000000000011', '00000001-0000-0000-0000-000000000002', 'PLANNED', 'Internship Planned', 1, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000012', '00000001-0000-0000-0000-000000000002', 'ACTIVE', 'Internship Active', 2, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000013', '00000001-0000-0000-0000-000000000002', 'COMPLETED', 'Internship Completed', 3, TRUE, now(), now(), 0);

-- 3. payment-approval
INSERT INTO workflow_definitions (id, code, name, description, active, version_num, created_at, updated_at, version)
VALUES (
    '00000001-0000-0000-0000-000000000003',
    'payment-approval',
    'Payment Approval Process',
    'Governs finance case review and stipend payment approval for eligible internships.',
    TRUE,
    1,
    now(),
    now(),
    0
);

INSERT INTO workflow_step_definitions (id, definition_id, code, name, sequence_num, required, created_at, updated_at, version)
VALUES
('00000002-0000-0000-0000-000000000021', '00000001-0000-0000-0000-000000000003', 'CASE_OPENED', 'Finance Case Opened', 1, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000022', '00000001-0000-0000-0000-000000000003', 'FINANCE_VERIFICATION', 'Finance Verification', 2, TRUE, now(), now(), 0),
('00000002-0000-0000-0000-000000000023', '00000001-0000-0000-0000-000000000003', 'PAYMENT_APPROVED', 'Payment Approved', 3, TRUE, now(), now(), 0);

