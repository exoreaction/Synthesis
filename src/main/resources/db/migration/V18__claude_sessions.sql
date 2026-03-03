-- Claude Code session history (episodic memory).
--
-- claude_sessions     : one row per indexed JSONL session file
-- claude_sessions_fts : FTS5 virtual table for full-text search across session content
--
-- Sessions are global (not workspace-specific) and stored in the shared Synthesis DB.
-- Populated by: synthesis sessions scan
-- Queried by:   synthesis sessions search / list / get, and the MCP 'sessions' tool

CREATE TABLE IF NOT EXISTS claude_sessions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT    NOT NULL UNIQUE,
    project_dir     TEXT    NOT NULL,     -- cwd extracted from first user message
    started_at      INTEGER NOT NULL,     -- epoch seconds of first user message
    ended_at        INTEGER,              -- epoch seconds of last message (nullable)
    turn_count      INTEGER NOT NULL DEFAULT 0,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    tool_names_json TEXT,                 -- JSON array of distinct tool names used
    first_message   TEXT,                 -- first user message text (opening intent)
    all_user_text   TEXT,                 -- concatenated user messages for FTS
    scanned_at      INTEGER NOT NULL      -- epoch seconds when this file was last scanned
);

CREATE INDEX IF NOT EXISTS idx_sessions_project
    ON claude_sessions(project_dir);
CREATE INDEX IF NOT EXISTS idx_sessions_started
    ON claude_sessions(started_at DESC);

-- ----------------------------------------------------------------
-- FTS5 virtual table for full-text search
-- content= keeps the FTS index in sync with the main table via triggers
-- ----------------------------------------------------------------

CREATE VIRTUAL TABLE IF NOT EXISTS claude_sessions_fts USING fts5(
    session_id UNINDEXED,
    project_dir,
    first_message,
    all_user_text,
    content=claude_sessions,
    content_rowid=id
);

-- Keep FTS index in sync with the main table

CREATE TRIGGER IF NOT EXISTS sessions_ai AFTER INSERT ON claude_sessions BEGIN
    INSERT INTO claude_sessions_fts(rowid, session_id, project_dir, first_message, all_user_text)
    VALUES (new.id, new.session_id, new.project_dir, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS sessions_au AFTER UPDATE ON claude_sessions BEGIN
    INSERT INTO claude_sessions_fts(claude_sessions_fts, rowid, session_id, project_dir, first_message, all_user_text)
    VALUES ('delete', old.id, old.session_id, old.project_dir, old.first_message, old.all_user_text);
    INSERT INTO claude_sessions_fts(rowid, session_id, project_dir, first_message, all_user_text)
    VALUES (new.id, new.session_id, new.project_dir, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS sessions_ad AFTER DELETE ON claude_sessions BEGIN
    INSERT INTO claude_sessions_fts(claude_sessions_fts, rowid, session_id, project_dir, first_message, all_user_text)
    VALUES ('delete', old.id, old.session_id, old.project_dir, old.first_message, old.all_user_text);
END;
