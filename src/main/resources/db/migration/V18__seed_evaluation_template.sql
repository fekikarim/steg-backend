-- ==============================================================================
-- V18__seed_evaluation_template.sql
-- Seed placeholder evaluation template with criteria summing to 100% (weight=100.0)
-- TODO — STEG VALIDATION REQUIRED: replace with official criteria
-- ==============================================================================

INSERT INTO evaluation_templates (id, name, description, active, version_num, created_at, updated_at, version)
VALUES (
    '00000003-0000-0000-0000-000000000001',
    'Standard STEG Internship Evaluation',
    'TODO — STEG VALIDATION REQUIRED: replace with official criteria. Placeholder template with standard 4-axis assessment summing to 100% weight.',
    TRUE,
    1,
    now(),
    now(),
    0
);

INSERT INTO evaluation_criteria (id, template_id, name, description, weight, max_score, created_at, updated_at, version)
VALUES
(
    '00000003-0000-0000-0000-000000000011',
    '00000003-0000-0000-0000-000000000001',
    'Technical Competence & Problem Solving',
    'Mastery of required technical skills, methodology, and quality of work executed during the internship.',
    30.00,
    20.00,
    now(),
    now(),
    0
),
(
    '00000003-0000-0000-0000-000000000012',
    '00000003-0000-0000-0000-000000000001',
    'Autonomy & Initiative',
    'Ability to work independently, demonstrate problem-solving initiative, and ask relevant questions.',
    25.00,
    20.00,
    now(),
    now(),
    0
),
(
    '00000003-0000-0000-0000-000000000013',
    '00000003-0000-0000-0000-000000000001',
    'Discipline, Punctuality & Integration',
    'Respect for STEG internal regulations, attendance, punctuality, and collaboration with team members.',
    25.00,
    20.00,
    now(),
    now(),
    0
),
(
    '00000003-0000-0000-0000-000000000014',
    '00000003-0000-0000-0000-000000000001',
    'Deliverables & Reporting Quality',
    'Quality, clarity, and timeliness of journal submissions, technical deliverables, and final reporting.',
    20.00,
    20.00,
    now(),
    now(),
    0
);
