-- Phase A13 — Back Office reporting read-model indexes.
-- The dashboards /api/reports/* run database-side GROUP BY aggregates over
-- the tables below; these indexes let PostgreSQL satisfy the grouped scans
-- without sequential scans as the data sets grow.

CREATE INDEX IF NOT EXISTS idx_reporting_applications_status
    ON internship_applications (status);

CREATE INDEX IF NOT EXISTS idx_reporting_internships_status
    ON internships (status);

CREATE INDEX IF NOT EXISTS idx_reporting_internships_type
    ON internships (type);

-- Department rollups traverse internship -> internship_assignments.
CREATE INDEX IF NOT EXISTS idx_reporting_assignments_internship
    ON internship_assignments (internship_id);

CREATE INDEX IF NOT EXISTS idx_reporting_assignments_department
    ON internship_assignments (department_id);

CREATE INDEX IF NOT EXISTS idx_reporting_finance_cases_status
    ON finance_cases (status);

-- Period rollups scan payment_receipts by payment date; the include()d amount
-- column turns the rollup into an index-only scan when receipts are paid.
CREATE INDEX IF NOT EXISTS idx_reporting_receipts_period_amount
    ON payment_receipts (payment_date) INCLUDE (amount);