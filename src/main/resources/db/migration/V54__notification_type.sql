-- T01 / BR-44 — stable notification type key.
--
-- Notifications were previously classified client-side only through
-- `related_entity_type` (the backend *entity* vocabulary: Task, Internship,
-- Conversation, ...), which cannot distinguish a task edit from a task delete
-- or the welcome message. A dedicated `type` column is the stable catalogue
-- key the mobile catalogue (D11) renders from.
--
-- Additive and nullable: legacy rows keep NULL and every client must render
-- them generically (never crash). New rows are always populated by the
-- NotificationService from the typed listener handlers.

ALTER TABLE notifications
    ADD COLUMN type VARCHAR(60);

COMMENT ON COLUMN notifications.type IS
    'Stable notification catalogue key (D11/BR-44): TASK_ASSIGNED, TASK_UPDATED, TASK_DELETED, TASK_STATUS_CHANGED, DOCUMENT_REJECTED, WELCOME, ... NULL for legacy rows (render generically).';
