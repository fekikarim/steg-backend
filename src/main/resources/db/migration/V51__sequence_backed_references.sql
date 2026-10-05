-- =========================================================
-- V51__sequence_backed_references.sql (task 4 follow-up to legacy-removal #25)
-- Count-then-write reference minting collides under concurrent generation
-- for distinct rows (proven for certificates in #25: 3/4 concurrent
-- generations died on the unique constraint). Move FC-/PAY-/DOC- numbering
-- to atomic database sequences, mirroring certificate_reference_seq (V46).
-- Formats stay readable and unchanged: FC-<year>-NNNNN, PAY-<year>-NNNNN,
-- DOC-<year>-NNNNN. Unique constraints stay as backstops.
-- =========================================================

CREATE SEQUENCE IF NOT EXISTS finance_case_reference_seq START 1;
CREATE SEQUENCE IF NOT EXISTS payment_receipt_reference_seq START 1;
CREATE SEQUENCE IF NOT EXISTS document_reference_seq START 1;
