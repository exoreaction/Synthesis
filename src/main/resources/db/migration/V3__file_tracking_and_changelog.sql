-- Synthesis v1.3.0: File Movement Tracking and Cross-Workspace Change Reporting
-- Created: 2026-02-16

-- ============================================================
-- FILE MOVEMENT TRACKING
-- ============================================================

-- Records detected file movements between workspaces or within a workspace
CREATE TABLE IF NOT EXISTS file_movements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    source_workspace TEXT,
    source_path TEXT NOT NULL,
    target_workspace TEXT,
    target_path TEXT,
    file_size INTEGER NOT NULL,
    file_type TEXT,
    status TEXT NOT NULL DEFAULT 'detected',
    detection_method TEXT NOT NULL,
    safety_expiry INTEGER,
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_fm_hash ON file_movements(content_hash);
CREATE INDEX IF NOT EXISTS idx_fm_status ON file_movements(status);
CREATE INDEX IF NOT EXISTS idx_fm_safety ON file_movements(safety_expiry);
CREATE INDEX IF NOT EXISTS idx_fm_source ON file_movements(source_workspace, source_path);
CREATE INDEX IF NOT EXISTS idx_fm_timestamp ON file_movements(timestamp);

-- Audit log for all actions taken on file movements
CREATE TABLE IF NOT EXISTS file_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    movement_id INTEGER REFERENCES file_movements(id),
    action TEXT NOT NULL,
    details TEXT
);

CREATE INDEX IF NOT EXISTS idx_fal_movement ON file_audit_log(movement_id);
CREATE INDEX IF NOT EXISTS idx_fal_timestamp ON file_audit_log(timestamp);

-- ============================================================
-- CROSS-WORKSPACE CHANGE REPORTING (SNAPSHOTS)
-- ============================================================

-- One row per snapshot event
CREATE TABLE IF NOT EXISTS workspace_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    workspace_name TEXT,
    snapshot_time INTEGER NOT NULL,
    file_count INTEGER NOT NULL,
    total_size_bytes INTEGER NOT NULL,
    trigger TEXT NOT NULL DEFAULT 'scheduled'
);

CREATE INDEX IF NOT EXISTS idx_ws_workspace ON workspace_snapshots(workspace_path);
CREATE INDEX IF NOT EXISTS idx_ws_time ON workspace_snapshots(snapshot_time);

-- Per-file entries within a snapshot
CREATE TABLE IF NOT EXISTS snapshot_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id INTEGER NOT NULL REFERENCES workspace_snapshots(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    content_hash TEXT,
    file_size INTEGER NOT NULL,
    last_modified INTEGER NOT NULL,
    file_type TEXT
);

CREATE INDEX IF NOT EXISTS idx_se_snapshot ON snapshot_entries(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_se_path ON snapshot_entries(relative_path);
CREATE INDEX IF NOT EXISTS idx_se_hash ON snapshot_entries(content_hash);

-- Change events detected between two snapshots
CREATE TABLE IF NOT EXISTS change_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    detected_time INTEGER NOT NULL,
    base_snapshot_id INTEGER REFERENCES workspace_snapshots(id),
    compare_snapshot_id INTEGER REFERENCES workspace_snapshots(id),
    change_type TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    previous_path TEXT,
    content_hash TEXT,
    file_size INTEGER,
    file_type TEXT,
    significance TEXT DEFAULT 'normal'
);

CREATE INDEX IF NOT EXISTS idx_ce_workspace ON change_events(workspace_path);
CREATE INDEX IF NOT EXISTS idx_ce_time ON change_events(detected_time);
CREATE INDEX IF NOT EXISTS idx_ce_significance ON change_events(significance);
CREATE INDEX IF NOT EXISTS idx_ce_type ON change_events(change_type);

-- Update metadata
INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_file_tracking', 'enabled');
INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_changelog', 'enabled');
