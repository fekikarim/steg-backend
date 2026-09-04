-- =========================================================
-- V4__candidate_application.sql: Universities, Candidates & Applications
-- =========================================================

CREATE TABLE universities (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_universities_code ON universities(code);

CREATE TABLE candidates (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE RESTRICT UNIQUE,
    university_id UUID NOT NULL REFERENCES universities(id) ON DELETE RESTRICT,
    national_id_encrypted TEXT,
    national_id_hash VARCHAR(255) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    birth_date DATE,
    address TEXT,
    speciality VARCHAR(255),
    diploma VARCHAR(255),
    skills TEXT,
    languages TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_candidates_user_id ON candidates(user_id);
CREATE INDEX idx_candidates_university_id ON candidates(university_id);
CREATE INDEX idx_candidates_national_id_hash ON candidates(national_id_hash);
CREATE INDEX idx_candidates_email ON candidates(email);

CREATE TABLE candidate_academic_profiles (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE CASCADE UNIQUE,
    education_level VARCHAR(50) NOT NULL,
    academic_year VARCHAR(50),
    speciality VARCHAR(255),
    diploma VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_academic_profiles_candidate_id ON candidate_academic_profiles(candidate_id);

CREATE TABLE internship_applications (
    id UUID PRIMARY KEY,
    reference VARCHAR(50) NOT NULL UNIQUE,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    reviewer_id UUID REFERENCES employees(id) ON DELETE RESTRICT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    submitted_online BOOLEAN NOT NULL DEFAULT TRUE,
    submission_date DATE,
    desired_start_date DATE,
    desired_end_date DATE,
    calculated_type VARCHAR(50),
    requirement VARCHAR(50),
    proposed_theme TEXT,
    rejection_reason TEXT,
    correction_comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_applications_reference ON internship_applications(reference);
CREATE INDEX idx_applications_candidate_id ON internship_applications(candidate_id);
CREATE INDEX idx_applications_status ON internship_applications(status);
CREATE INDEX idx_applications_reviewer_id ON internship_applications(reviewer_id);
