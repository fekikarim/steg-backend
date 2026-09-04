-- =========================================================
-- V5__internship_assignment.sql: Internships & Assignments
-- =========================================================

CREATE TABLE internships (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    application_id UUID REFERENCES internship_applications(id) ON DELETE RESTRICT UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PLANNED',
    type VARCHAR(50) NOT NULL,
    requirement VARCHAR(50) NOT NULL,
    subject TEXT,
    academic_level VARCHAR(100),
    planned_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_internships_reference ON internships(reference);
CREATE INDEX idx_internships_candidate_id ON internships(candidate_id);
CREATE INDEX idx_internships_application_id ON internships(application_id);
CREATE INDEX idx_internships_status ON internships(status);

CREATE TABLE internship_assignments (
    id UUID PRIMARY KEY,
    internship_id UUID NOT NULL REFERENCES internships(id) ON DELETE CASCADE,
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE RESTRICT,
    supervisor_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    assigned_by_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    assigned_at DATE NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PLANNED',
    assignment_reason TEXT,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Partial unique index enforcing at most one ACTIVE assignment per Internship
CREATE UNIQUE INDEX idx_internship_assignment_active_unique
    ON internship_assignments (internship_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_assignments_internship_id ON internship_assignments(internship_id);
CREATE INDEX idx_assignments_dept_id ON internship_assignments(department_id);
CREATE INDEX idx_assignments_supervisor_id ON internship_assignments(supervisor_id);
CREATE INDEX idx_assignments_assigned_by_id ON internship_assignments(assigned_by_id);
CREATE INDEX idx_assignments_status ON internship_assignments(status);
