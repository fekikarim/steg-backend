-- Pre-check (c) — soft delete vs. re-registration (audit assumption #13).
--
-- S5 introduced candidates.deleted_at but left the two identity UNIQUE
-- constraints from V4 in place, so a soft-deleted profile kept owning them:
--   * candidates.user_id UNIQUE  -> the person's account stayed reserved and a
--     second POST /api/candidates from the same account died on the DB
--     constraint (409) even though the service comment promised the opposite;
--   * candidates.national_id_hash UNIQUE -> the CIN could never be registered
--     again, so a person whose profile was deleted was locked out forever.
--
-- A CIN is a permanent national identity number: the SAME person always
-- presents the SAME CIN. Reserving it on a deleted row therefore locks out the
-- legitimate owner and reserves it for nobody, while two LIVE profiles must
-- still never share one. Uniqueness is moved to the live rows only.
--
-- user_id keeps its plain UNIQUE (Postgres allows many NULLs), so "one LIVE
-- profile per account" stays a database guarantee while the soft-delete
-- command releases the account by setting user_id = NULL.
ALTER TABLE candidates DROP CONSTRAINT IF EXISTS candidates_national_id_hash_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_candidates_live_national_id_hash
    ON candidates (national_id_hash)
    WHERE deleted_at IS NULL;
