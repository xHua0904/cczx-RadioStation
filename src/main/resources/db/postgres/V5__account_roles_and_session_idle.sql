ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'editor' CHECK (role IN ('admin', 'editor'));
UPDATE accounts SET role='admin' WHERE username='新媒体中心';
ALTER TABLE sessions ADD COLUMN last_seen_at TEXT NOT NULL DEFAULT '';
DELETE FROM sessions;
