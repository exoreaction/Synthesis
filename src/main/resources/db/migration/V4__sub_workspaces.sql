-- Synthesis v1.4.0: Sub-Workspace Architecture
-- Created: 2026-02-16
--
-- Adds support for logical sub-workspaces within a workspace.
-- Sub-workspaces enable scoped search, per-partition analytics,
-- and staging workflows for incoming files.

-- ============================================================
-- SUB-WORKSPACE REGISTRY
-- ============================================================

-- Registered sub-workspaces for each workspace
CREATE TABLE IF NOT EXISTS sub_workspaces (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    name TEXT NOT NULL,
    path_prefix TEXT NOT NULL,
    description TEXT,
    type TEXT NOT NULL DEFAULT 'general',
    tags TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(workspace_path, name)
);

CREATE INDEX IF NOT EXISTS idx_sw_workspace ON sub_workspaces(workspace_path);
CREATE INDEX IF NOT EXISTS idx_sw_name ON sub_workspaces(name);

-- ============================================================
-- STAGING FILE TRACKING
-- ============================================================

-- Tracks files in staging sub-workspaces with retention metadata
CREATE TABLE IF NOT EXISTS staging_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    sub_workspace TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    file_type TEXT,
    content_hash TEXT,
    classified_org TEXT,
    classification_confidence REAL DEFAULT 0.0,
    suggested_destination TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    ingested_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    promoted_at INTEGER,
    promoted_to TEXT,
    UNIQUE(workspace_path, relative_path)
);

CREATE INDEX IF NOT EXISTS idx_sf_workspace ON staging_files(workspace_path);
CREATE INDEX IF NOT EXISTS idx_sf_status ON staging_files(status);
CREATE INDEX IF NOT EXISTS idx_sf_expires ON staging_files(expires_at);
CREATE INDEX IF NOT EXISTS idx_sf_org ON staging_files(classified_org);

-- ============================================================
-- SUB-WORKSPACE STATISTICS
-- ============================================================

-- Per-sub-workspace file counts and metrics (updated on scan/maintain)
CREATE TABLE IF NOT EXISTS sub_workspace_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    sub_workspace TEXT NOT NULL,
    snapshot_time INTEGER NOT NULL,
    file_count INTEGER NOT NULL DEFAULT 0,
    total_size_bytes INTEGER NOT NULL DEFAULT 0,
    code_files INTEGER NOT NULL DEFAULT 0,
    doc_files INTEGER NOT NULL DEFAULT 0,
    media_files INTEGER NOT NULL DEFAULT 0,
    other_files INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sws_workspace ON sub_workspace_stats(workspace_path, sub_workspace);
CREATE INDEX IF NOT EXISTS idx_sws_time ON sub_workspace_stats(snapshot_time);

-- Update metadata
INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_sub_workspaces', 'enabled');
INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_staging', 'enabled');
