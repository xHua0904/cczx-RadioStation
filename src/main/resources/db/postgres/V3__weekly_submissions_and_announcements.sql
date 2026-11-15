ALTER TABLE submissions ADD COLUMN period TEXT NOT NULL DEFAULT '';
ALTER TABLE submissions ADD COLUMN broadcast_time TEXT NOT NULL DEFAULT '';
UPDATE submissions SET period = to_char(to_date(substr(created_at, 1, 10), 'YYYY-MM-DD'), 'IYYY-"W"IW');
CREATE INDEX idx_submissions_period ON submissions(period);
CREATE INDEX idx_submissions_schedule ON submissions(broadcast_date, broadcast_time);
CREATE TABLE announcements (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT '公告' CHECK (kind IN ('公告', '投稿计划')),
    period TEXT NOT NULL DEFAULT '',
    publisher TEXT NOT NULL DEFAULT '新媒体中心',
    published INTEGER NOT NULL DEFAULT 1 CHECK (published IN (0, 1)),
    created_at TEXT NOT NULL,
    published_at TEXT NOT NULL
);
