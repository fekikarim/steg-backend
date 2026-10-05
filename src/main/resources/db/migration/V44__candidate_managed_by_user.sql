-- S6a — staff-created candidate profile (AGENTS.md §5.1 / §6.2, ambiguity A4).
--
-- Until now a candidate's supervision scope was derived ONLY from internships
-- (an ACTIVE InternshipAssignment or internship.supervisor_user_id). That works
-- for a candidate who went through approval, but AGENTS.md §5.1 asks staff to be
-- able to CREATE a candidate — and A4 says that creating one "links it to that
-- supervisor". A profile created by staff has no internship yet (approval stays
-- Admin-only), so with the old model it would be invisible to the very
-- Supervisor who created it.
--
-- managed_by_user_id stores that link at creation time:
--   * NULL for every profile that came from the front office / anonymous intake;
--   * the acting Admin or Supervisor for a staff-created profile;
--   * ON DELETE SET NULL so deleting a staff account never orphans or deletes a
--     candidate row.
--
-- The scope RULE itself (assignment OR managed_by) lives only in
-- SupervisionScopeService — this column is data, not a second rule.
ALTER TABLE candidates
    ADD COLUMN managed_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL;

-- The scope query filters live rows by manager; a partial index keeps it cheap
-- and mirrors idx_candidates_live_national_id_hash (V42).
CREATE INDEX IF NOT EXISTS idx_candidates_managed_by_user
    ON candidates (managed_by_user_id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN candidates.managed_by_user_id IS
    'Staff member who manages this candidate (AGENTS.md A4). NULL when the profile '
    'came from the front office; the supervision scope then derives from the internship assignment.';