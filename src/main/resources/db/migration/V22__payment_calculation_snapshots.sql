-- =========================================================
-- V22__payment_calculation_snapshots.sql: Phase A11 immutable snapshots
-- A recalculation stores a NEW PaymentCalculation row (new id, new
-- calculatedAt) instead of replacing the previous one: the full version
-- history is preserved in-table, on top of the audit old/new JSON trail.
-- The current snapshot is the highest calculation_sequence per case.
-- =========================================================

ALTER TABLE payment_calculations
    DROP CONSTRAINT IF EXISTS payment_calculations_finance_case_id_key;

ALTER TABLE payment_calculations
    ADD COLUMN IF NOT EXISTS calculation_sequence INT NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_calc_case_seq
    ON payment_calculations(finance_case_id, calculation_sequence);
