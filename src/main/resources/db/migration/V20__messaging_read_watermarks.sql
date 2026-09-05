-- =========================================================
-- V20__messaging_read_watermarks.sql: Phase A9 production gate
-- Sequence-based per-member read/delivery watermarks.
-- Ordering is authoritative via messages.sequenceNumber, never via
-- timestamps; conversation_members.last_read_at is retained only as a
-- wall-clock audit marker.
-- Backfill: existing members keep NULL watermarks (= nothing acknowledged,
-- unread counts compute from sequence 0), preserving current behavior.
-- =========================================================

ALTER TABLE conversation_members
    ADD COLUMN IF NOT EXISTS last_read_sequence_number BIGINT;

ALTER TABLE conversation_members
    ADD COLUMN IF NOT EXISTS last_delivered_sequence_number BIGINT;
