-- =========================================================
-- V19__messaging_constraints.sql: Phase A9 hardening
-- - Monotonic sequenceNumber per conversation (defensive unique guard;
--   the service serializes sends via SELECT ... FOR UPDATE on conversations)
-- - At most one PRIVATE thread per internship (Internship.privateThread 0..1)
-- - Fast active-membership lookups (leftAt IS NULL)
-- =========================================================

-- Defensive uniqueness for message ordering within a conversation.
-- Concurrent sends are serialized in MessagingService via a pessimistic
-- lock on the parent conversation row; this constraint is a second guard
-- so a race can never silently produce duplicate sequence numbers.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_messages_conv_seq'
    ) THEN
        ALTER TABLE messages
            ADD CONSTRAINT uq_messages_conv_seq UNIQUE (conversation_id, sequence_number);
    END IF;
END
$$;

-- Exactly one PRIVATE thread per internship (GROUP threads have internship_id NULL).
CREATE UNIQUE INDEX IF NOT EXISTS uq_private_thread_per_internship
    ON conversations(internship_id)
    WHERE type = 'PRIVATE' AND internship_id IS NOT NULL;

-- Active membership lookups used on every send/read/history operation.
CREATE INDEX IF NOT EXISTS idx_conv_members_active
    ON conversation_members(conversation_id, user_id)
    WHERE left_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conv_members_user_active
    ON conversation_members(user_id)
    WHERE left_at IS NULL;
