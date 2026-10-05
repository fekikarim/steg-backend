UPDATE internship_applications
SET status = 'APPROVED'
WHERE status = 'ACCEPTED';

UPDATE internship_applications
SET status = 'MODIFICATION_REQUESTED'
WHERE status = 'NEEDS_CORRECTION';

UPDATE internships
SET status = 'APPROVED'
WHERE status = 'PLANNED'
  AND application_id IN (
      SELECT id FROM internship_applications WHERE status = 'APPROVED'
  );

CREATE INDEX IF NOT EXISTS idx_applications_explicit_status
    ON internship_applications(status);
CREATE INDEX IF NOT EXISTS idx_internships_explicit_status
    ON internships(status);