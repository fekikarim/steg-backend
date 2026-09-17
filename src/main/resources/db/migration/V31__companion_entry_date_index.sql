-- E8: Composite indexes for companion journal entries query optimization.
--
-- 1. idx_journal_entries_journal_date:
--    Supports findByJournalIdAndEntryDateBetween. Without this index, filtering
--    journal entries across a date range on a journal with thousands of entries
--    requires scanning all entries for that journal.
--
-- 2. idx_journal_entries_journal_status_date:
--    Supports findByJournalIdAndStatusAndEntryDateBetween. Allows PostgreSQL
--    to filter on journal_id + status and range-scan on entry_date in a single index pass.

CREATE INDEX IF NOT EXISTS idx_journal_entries_journal_date
    ON journal_entries (journal_id, entry_date);

CREATE INDEX IF NOT EXISTS idx_journal_entries_journal_status_date
    ON journal_entries (journal_id, status, entry_date);
