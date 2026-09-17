-- =========================================================
-- V29__reference_universities.sql: E2 reference data (clean-DB onboarding)
-- =========================================================
-- Public Tunisian higher-education institutions. Reference data (like V15
-- roles / V16 workflow definitions), NOT demo data: candidate onboarding
-- requires a university, and no admin endpoint creates them. Real public
-- institution names; no personal data.

INSERT INTO universities (id, code, name, active, created_at, updated_at, version) VALUES
('f0000000-0000-0000-0000-000000000001', 'INSAT', 'Institut National des Sciences Appliquées et de Technologie', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000002', 'ENIT', 'École Nationale d''Ingénieurs de Tunis', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000003', 'ENISO', 'École Nationale d''Ingénieurs de Sousse', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000004', 'FST', 'Faculté des Sciences de Tunis', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000005', 'ESPRIT', 'École Supérieure Privée d''Ingénierie et de Technologies', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000006', 'IHEC', 'Institut des Hautes Études Commerciales', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000007', 'FSEG', 'Faculté des Sciences Économiques et de Gestion', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000008', 'ISG', 'Institut Supérieur de Gestion de Tunis', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000009', 'ENIG', 'École Nationale d''Ingénieurs de Gabès', TRUE, now(), now(), 0),
('f0000000-0000-0000-0000-000000000010', 'ISTIC', 'Institut Supérieur des Technologies de l''Information et de la Communication', TRUE, now(), now(), 0)
ON CONFLICT (code) DO NOTHING;
