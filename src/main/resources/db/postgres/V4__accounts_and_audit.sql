CREATE TABLE accounts (
    username TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    password_encrypted TEXT NOT NULL DEFAULT '',
    initial_password INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
ALTER TABLE sessions ADD COLUMN username TEXT NOT NULL DEFAULT '';
DELETE FROM sessions;
ALTER TABLE submissions ADD COLUMN reviewed_by TEXT NOT NULL DEFAULT '';
ALTER TABLE submissions ADD COLUMN scheduled_by TEXT NOT NULL DEFAULT '';
ALTER TABLE submissions ADD COLUMN updated_by TEXT NOT NULL DEFAULT '';
ALTER TABLE announcements ADD COLUMN updated_by TEXT NOT NULL DEFAULT '';
ALTER TABLE announcements ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    username TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    target_label TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_audit_target ON audit_log(target_type, target_id, id);
