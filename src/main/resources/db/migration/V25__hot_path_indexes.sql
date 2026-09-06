-- Phase A14 — hot-path indexes for the N+1 remediation.
--
-- 1. idx_wf_inst_finance_case_id: the finance-case list view bulk-loads payment
--    workflow instance ids with WHERE finance_case_id IN (...). Without this
--    index every page would sequential-scan workflow_instances.
-- 2. idx_assignments_internship_status: the active-assignment lookup
--    (internship_id + status) gates nearly every membership/authorization check
--    (messaging, companion, evaluation, certificates). The composite lets
--    PostgreSQL answer it as a single index scan instead of combining two
--    single-column indexes.

CREATE INDEX IF NOT EXISTS idx_wf_inst_finance_case_id
    ON workflow_instances (finance_case_id);

CREATE INDEX IF NOT EXISTS idx_assignments_internship_status
    ON internship_assignments (internship_id, status);
