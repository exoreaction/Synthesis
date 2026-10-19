-- Confluence workspace source: page index and sync state (RFC #541, Phase 1)

CREATE TABLE IF NOT EXISTS confluence_pages (
    page_id        TEXT NOT NULL,
    workspace_name TEXT NOT NULL,
    space_key      TEXT,
    title          TEXT NOT NULL,
    parent_page_id TEXT,
    virtual_path   TEXT NOT NULL,
    last_edited_at INTEGER NOT NULL,
    last_synced_at INTEGER NOT NULL,
    content_hash   TEXT,
    page_url       TEXT,
    labels         TEXT,
    PRIMARY KEY (page_id, workspace_name)
);

CREATE INDEX IF NOT EXISTS idx_confluence_pages_workspace
    ON confluence_pages(workspace_name);
CREATE INDEX IF NOT EXISTS idx_confluence_pages_parent
    ON confluence_pages(parent_page_id);
CREATE INDEX IF NOT EXISTS idx_confluence_pages_virtual_path
    ON confluence_pages(virtual_path, workspace_name);
CREATE INDEX IF NOT EXISTS idx_confluence_pages_last_edited
    ON confluence_pages(last_edited_at);
CREATE INDEX IF NOT EXISTS idx_confluence_pages_space
    ON confluence_pages(space_key);

CREATE TABLE IF NOT EXISTS confluence_sync_state (
    workspace_name    TEXT PRIMARY KEY,
    confluence_space_keys TEXT NOT NULL,
    last_sync_time    INTEGER NOT NULL,
    total_pages       INTEGER NOT NULL DEFAULT 0,
    last_sync_status  TEXT NOT NULL DEFAULT 'ok',
    error_message     TEXT
);
