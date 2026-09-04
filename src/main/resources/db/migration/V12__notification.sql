-- =========================================================
-- V12__notification.sql: Notifications & Deliveries
-- =========================================================

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    priority VARCHAR(50) NOT NULL DEFAULT 'NORMAL',
    related_entity_type VARCHAR(100),
    related_entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_notifications_priority ON notifications(priority);
CREATE INDEX idx_notifications_rel_entity ON notifications(related_entity_type, related_entity_id);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_notif_deliv_notif_id ON notification_deliveries(notification_id);
CREATE INDEX idx_notif_deliv_recipient_id ON notification_deliveries(recipient_id);
CREATE INDEX idx_notif_deliv_status ON notification_deliveries(status);
