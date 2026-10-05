-- =========================================================
-- V53__user_credentials_updated_at.sql (Task 3: session revocation)
--
-- Access tokens are stateless JWTs; the JWT filter re-checks the account
-- state on every request (deactivated/deleted accounts are refused at once),
-- but an ADMIN PASSWORD RESET does not disable the account — the old access
-- token must still stop working the moment the secret changes. This column
-- is the credential version: every token issued BEFORE it is refused.
--
-- Set by the admin password reset (the forced-logout case). A self-service
-- password change deliberately does NOT bump it: the person proving the
-- current password keeps their current session (the bootstrap-created admin
-- forces the change at first login instead).
-- =========================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS credentials_updated_at TIMESTAMPTZ;
