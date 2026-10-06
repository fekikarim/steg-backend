-- =========================================================
-- V57__community.sql: Student Community Feed (T08 / ST-COM-01/02, D7)
-- =========================================================
--
-- One community for all students with an active internship (BR-39):
-- posts + comments + reports + mutes. Staff (ADMIN/SUPERVISOR) read and
-- moderate; only active-internship students may write. Every moderation
-- action is audited (application layer); author display names are derived
-- server-side from the candidate record (never email/CIN/university).
--
-- Content lifecycle: VISIBLE -> DELETED (own author) | REMOVED (moderator
-- with mandatory reason). Removed/deleted rows are hidden from the feed
-- and answer 404 (POST_REMOVED/COMMENT_REMOVED when the row exists but is
-- gone, plain 404 when it never existed — no existence leak).

CREATE TABLE community_posts (
    id UUID PRIMARY KEY,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    body_hash VARCHAR(64) NOT NULL,
    attachment_asset_id UUID REFERENCES file_assets(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    removed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    removed_reason TEXT,
    comment_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_community_posts_status
        CHECK (status IN ('VISIBLE', 'DELETED', 'REMOVED'))
);

-- Newest-first feed order (created_at DESC, id DESC tiebreak) + author
-- duplicate-content window (author + recent created_at).
CREATE INDEX idx_community_posts_feed
    ON community_posts(status, created_at DESC, id DESC);
CREATE INDEX idx_community_posts_author_recent
    ON community_posts(author_user_id, created_at DESC);
CREATE INDEX idx_community_posts_attachment
    ON community_posts(attachment_asset_id)
    WHERE attachment_asset_id IS NOT NULL;

COMMENT ON TABLE community_posts IS
    'T08/ST-COM student community posts (D7): one feed for active-internship students; staff read+moderate. VISIBLE/DELETED(author)/REMOVED(moderator+reason).';
COMMENT ON COLUMN community_posts.body_hash IS
    'SHA-256 of the normalized body (trim + collapse whitespace, lowercased) for the 10-minute duplicate-content guard.';

CREATE TABLE community_comments (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES community_posts(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    removed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    removed_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_community_comments_status
        CHECK (status IN ('VISIBLE', 'DELETED', 'REMOVED'))
);

-- Chronological thread order (created_at ASC, id ASC tiebreak).
CREATE INDEX idx_community_comments_post_thread
    ON community_comments(post_id, status, created_at ASC, id ASC);
CREATE INDEX idx_community_comments_author
    ON community_comments(author_user_id);

COMMENT ON TABLE community_comments IS
    'T08/ST-COM comments on community posts: flat (no threading), text-only, same lifecycle as posts.';

CREATE TABLE community_reports (
    id UUID PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_post_id UUID REFERENCES community_posts(id) ON DELETE CASCADE,
    target_comment_id UUID REFERENCES community_comments(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution TEXT,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_community_reports_target
        CHECK (target_type IN ('POST', 'COMMENT')),
    CONSTRAINT ck_community_reports_single_target
        CHECK ((target_post_id IS NOT NULL AND target_comment_id IS NULL AND target_type = 'POST')
            OR (target_post_id IS NULL AND target_comment_id IS NOT NULL AND target_type = 'COMMENT')),
    CONSTRAINT ck_community_reports_status
        CHECK (status IN ('OPEN', 'RESOLVED'))
);

-- Moderation queue order + one-OPEN-report-per-reporter-and-target guard
-- (enforced in the service; index keeps the lookup cheap).
CREATE INDEX idx_community_reports_queue
    ON community_reports(status, created_at DESC, id DESC);
CREATE INDEX idx_community_reports_post
    ON community_reports(target_post_id)
    WHERE target_post_id IS NOT NULL;
CREATE INDEX idx_community_reports_comment
    ON community_reports(target_comment_id)
    WHERE target_comment_id IS NOT NULL;
CREATE INDEX idx_community_reports_reporter
    ON community_reports(reporter_user_id, status);

COMMENT ON TABLE community_reports IS
    'T08/ST-COM abuse reports (D7): reporter identity visible to staff only, never to authors. One OPEN report per reporter+target.';

CREATE TABLE community_mutes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted_by UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    muted_until TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_community_mutes_user UNIQUE (user_id)
);

CREATE INDEX idx_community_mutes_until ON community_mutes(muted_until);

COMMENT ON TABLE community_mutes IS
    'T08/ST-COM community write bans (D7): muted authors cannot post/comment until muted_until. Moderator identity (muted_by) never leaves the server.';
