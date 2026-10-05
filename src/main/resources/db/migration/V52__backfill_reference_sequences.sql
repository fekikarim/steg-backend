-- =========================================================
-- V52__backfill_reference_sequences.sql (production-critical sequence init)
--
-- V51 created finance_case_reference_seq / payment_receipt_reference_seq /
-- document_reference_seq at START 1. But every real database that predates
-- V51 minted its FC-/PAY-/DOC- references with the OLD count-based scheme,
-- which numbers with the CURRENT year — so a live database holds rows like
-- FC-2026-00001 .. FC-2026-000NN. A sequence starting at 1 re-mints exactly
-- those low numbers and dies on the reference UNIQUE constraint the first
-- time a finance case, receipt or document is created after the upgrade.
--
-- Fix: move each sequence PAST the highest numeric suffix already present in
-- its table. setval(seq, max + 1, false) makes the very next nextval() return
-- exactly max + 1 — strictly greater than every existing suffix — while an
-- empty table keeps the sequence at 1 (identical to V51's fresh behavior:
-- COALESCE(MAX, 0) + 1 = 1 with is_called = false).
--
-- certificate_reference_seq (V46) has the same latent defect class for any
-- database that already held count-based CERT- rows when V46 was applied, so
-- it is backfilled here too (idempotent and empty-DB safe, exactly like the
-- other three).
--
-- Idempotent: re-running against an already-advanced sequence only ever moves
-- it forward to table-max + 1 (sequences are never moved backward, so rows
-- minted after V51/V52 keep their numbers). Suffix parsing is restricted to
-- service-minted shapes (^PREFIX-YYYY-N+), so hand-written test/reference
-- values ('CERT-REF-001', 'FC-RG-…') can never poison the backfill.
-- =========================================================

SELECT setval('finance_case_reference_seq',
       COALESCE(MAX((split_part(reference, '-', 3))::bigint), 0) + 1, false)
FROM finance_cases
WHERE reference ~ '^FC-[0-9]{4}-[0-9]+$';

SELECT setval('payment_receipt_reference_seq',
       COALESCE(MAX((split_part(reference, '-', 3))::bigint), 0) + 1, false)
FROM payment_receipts
WHERE reference ~ '^PAY-[0-9]{4}-[0-9]+$';

SELECT setval('document_reference_seq',
       COALESCE(MAX((split_part(reference, '-', 3))::bigint), 0) + 1, false)
FROM documents
WHERE reference ~ '^DOC-[0-9]{4}-[0-9]+$';

SELECT setval('certificate_reference_seq',
       COALESCE(MAX((split_part(reference, '-', 3))::bigint), 0) + 1, false)
FROM certificates
WHERE reference ~ '^CERT-[0-9]{4}-[0-9]+$';
