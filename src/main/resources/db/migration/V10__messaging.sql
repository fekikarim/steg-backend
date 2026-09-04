-- =========================================================
-- V10__messaging.sql: Real-Time Messaging & Conversations
-- =========================================================

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    type VARCHAR(50) NOT NULL DEFAULT 'PRIVATE',
    title VARCHAR(255),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    internship_id UUID REFERENCES internships(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_conversations_internship_id ON conversations(internship_id);

CREATE TABLE conversation_members (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    last_read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_conversation_member UNIQUE (conversation_id, user_id)
);

CREATE INDEX idx_conv_members_conv_id ON conversation_members(conversation_id);
CREATE INDEX idx_conv_members_user_id ON conversation_members(user_id);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SENT',
    sequence_number BIGINT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Index for sequence lookup within conversation
CREATE INDEX idx_messages_conv_seq ON messages(conversation_id, sequence_number);
CREATE INDEX idx_messages_sender_id ON messages(sender_id);

CREATE TABLE message_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id) ON DELETE RESTRICT,
    attached_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_msg_attachments_msg_id ON message_attachments(message_id);
CREATE INDEX idx_msg_attachments_file_id ON message_attachments(file_asset_id);
