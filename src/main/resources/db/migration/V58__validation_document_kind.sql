-- =========================================================
-- V58__validation_document_kind.sql: explicit validation document kind
-- (T10 / B8 + SU-VAL-01, D4b, BR-33, ST-VAL-03)
-- =========================================================
--
-- B8: the journal and the report become DATA instead of inference. Until now
-- the back office resolved them by deliverable order (assumption #18:
-- report = oldest submitted, journal = newest submitted non-report), which the
-- mobile submission flow makes fragile. A deliverable may now carry an
-- explicit kind (REPORT / JOURNAL, the existing ValidationDocumentType), set
-- by the student at upload or by the supervising first-level review
-- (SU-VAL-01). Resolution prefers explicit kinds and falls back to the
-- documented order for legacy rows, per type, so a half-marked internship
-- never breaks the Admin queue.
--
-- SU-VAL-01: a chat attachment sent from one of the internship's documents
-- records which deliverable it came from, so the supervisor's "set as
-- journal / set as report" action targets exactly the document received.
-- Nullable: ordinary chat files have no source document.
--
-- No data migration: existing rows keep NULL (legacy inference unchanged).

ALTER TABLE deliverables
    ADD COLUMN document_kind VARCHAR(20);

ALTER TABLE message_attachments
    ADD COLUMN source_deliverable_id UUID REFERENCES deliverables(id) ON DELETE SET NULL;

CREATE INDEX idx_deliverables_document_kind
    ON deliverables (internship_id, document_kind);
