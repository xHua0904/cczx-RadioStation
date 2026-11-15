CREATE TABLE submissions (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    author TEXT NOT NULL,
    class_name TEXT NOT NULL,
    contact TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT '待审核' CHECK (status IN ('待审核', '已通过', '已播出', '已退稿')),
    score INTEGER CHECK (score BETWEEN 0 AND 100),
    broadcast_date TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT '在线投稿',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_submissions_created ON submissions(created_at);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_class ON submissions(class_name);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE sessions (token TEXT PRIMARY KEY, created_at TEXT NOT NULL, expires_at TEXT NOT NULL);
CREATE TABLE counters (day TEXT PRIMARY KEY, n INTEGER NOT NULL);
