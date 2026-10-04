-- Initial Schema for ComTrak (SQLite)

PRAGMA foreign_keys = ON;

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    google_sub TEXT UNIQUE NOT NULL,
    email TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_users_google_sub ON users(google_sub);

-- 2. Commissions Table
CREATE TABLE IF NOT EXISTS commissions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    customer_name TEXT NOT NULL,
    customer_phone TEXT,
    customer_email TEXT,
    item_name TEXT NOT NULL,
    size TEXT,
    colour TEXT,
    quantity INTEGER NOT NULL DEFAULT 1,
    price_cents INTEGER,
    due_date INTEGER,
    status TEXT NOT NULL DEFAULT 'requiring_details',
    clay_body TEXT,
    glazes TEXT,
    thumbnail_media_id TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    version INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_commissions_user_updated ON commissions(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_commissions_user_due_date ON commissions(user_id, due_date);
CREATE INDEX IF NOT EXISTS idx_commissions_status ON commissions(user_id, status);

-- 3. Commission Updates / Timeline Table
CREATE TABLE IF NOT EXISTS commission_updates (
    id TEXT PRIMARY KEY,
    commission_id TEXT NOT NULL REFERENCES commissions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_status TEXT,
    to_status TEXT,
    note TEXT,
    created_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_updates_commission ON commission_updates(commission_id, created_at);
CREATE INDEX IF NOT EXISTS idx_updates_user_created ON commission_updates(user_id, created_at);

-- 4. Media Attachments Table
CREATE TABLE IF NOT EXISTS media_attachments (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commission_id TEXT NOT NULL REFERENCES commissions(id) ON DELETE CASCADE,
    update_id TEXT REFERENCES commission_updates(id) ON DELETE SET NULL,
    media_type TEXT NOT NULL, -- 'reference', 'progress', 'finished'
    s3_key TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'image/webp',
    width INTEGER,
    height INTEGER,
    file_size_bytes INTEGER,
    created_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_media_commission ON media_attachments(commission_id);
CREATE INDEX IF NOT EXISTS idx_media_update ON media_attachments(update_id);
CREATE INDEX IF NOT EXISTS idx_media_user_created ON media_attachments(user_id, created_at);
