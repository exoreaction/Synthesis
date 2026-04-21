-- Notion workspace source: page index and sync state (Phase 1)

CREATE TABLE IF NOT EXISTS notion_pages (
    page_id        TEXT NOT NULL,
    workspace_name TEXT NOT NULL,
    title          TEXT NOT NULL,
    parent_page_id TEXT,
    virtual_path   TEXT NOT NULL,
    last_edited_at INTEGER NOT NULL,
    last_synced_at INTEGER NOT NULL,
    content_hash   TEXT,
    page_url       TEXT,
    is_database    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (page_id, workspace_name)
);

CREATE INDEX IF NOT EXISTS idx_notion_pages_workspace
    ON notion_pages(workspace_name);
CREATE INDEX IF NOT EXISTS idx_notion_pages_parent
    ON notion_pages(parent_page_id);
CREATE INDEX IF NOT EXISTS idx_notion_pages_virtual_path
    ON notion_pages(virtual_path, workspace_name);
CREATE INDEX IF NOT EXISTS idx_notion_pages_last_edited
    ON notion_pages(last_edited_at);

CREATE TABLE IF NOT EXISTS notion_sync_state (
    workspace_name   TEXT PRIMARY KEY,
    notion_root_id   TEXT NOT NULL,
    last_sync_time   INTEGER NOT NULL,
    total_pages      INTEGER NOT NULL DEFAULT 0,
    last_sync_status TEXT NOT NULL DEFAULT 'ok',
    error_message    TEXT
);
