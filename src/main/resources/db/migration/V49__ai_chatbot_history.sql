-- S10b — back-office chatbot conversation history (AGENTS.md §7.5).
--
-- Turns are per user and server-side: the owner is the users row, so a user
-- can never read another user's history. Bounded by the service (latest N
-- turns kept); message bodies are user/model text — audit rows reference
-- counts only, never content.

CREATE TABLE ai_chat_messages (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_messages_user_created
    ON ai_chat_messages (user_id, created_at DESC);

COMMENT ON TABLE ai_chat_messages IS
    'S10b chatbot turns (AGENTS.md §7.5): per-user server-side history, bounded by the service.';
