-- Git file metrics: temporal hotspot scoring, bus factor, co-change coupling.
-- Populated by GitMetricsComputer via `synthesis hotspots --refresh`.
-- The SQLite tables are a cache -- they can be recomputed at any time from git history.

CREATE TABLE IF NOT EXISTS git_file_metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,
    hotspot_score REAL NOT NULL DEFAULT 0.0,
    commit_count_total INTEGER NOT NULL DEFAULT 0,
    commit_count_90d INTEGER NOT NULL DEFAULT 0,
    commit_count_30d INTEGER NOT NULL DEFAULT 0,
    author_count INTEGER NOT NULL DEFAULT 0,
    bus_factor INTEGER NOT NULL DEFAULT 1,
    last_commit_at INTEGER,
    computed_at INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path)
);

CREATE INDEX IF NOT EXISTS idx_git_file_metrics_workspace
    ON git_file_metrics(workspace_path);
CREATE INDEX IF NOT EXISTS idx_git_file_metrics_hotspot
    ON git_file_metrics(workspace_path, hotspot_score DESC);

CREATE TABLE IF NOT EXISTS git_cochange (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_a TEXT NOT NULL,
    file_b TEXT NOT NULL,
    coupling_score REAL NOT NULL DEFAULT 0.0,
    cochange_count INTEGER NOT NULL DEFAULT 0,
    last_cochange_at INTEGER,
    computed_at INTEGER NOT NULL,
    UNIQUE(workspace_path, file_a, file_b)
);

CREATE INDEX IF NOT EXISTS idx_git_cochange_lookup
    ON git_cochange(workspace_path, file_a, coupling_score DESC);
