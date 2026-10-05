ALTER TABLE tasks
    ADD COLUMN review_reason TEXT,
    ADD COLUMN reviewed_by_id UUID REFERENCES users(id),
    ADD COLUMN reviewed_at TIMESTAMPTZ;