-- V10__directory_centroids.sql
-- Phase 2: Stores computed centroid data and per-file enrichment signatures

-- Stores computed centroid data per directory per workspace
CREATE TABLE IF NOT EXISTS directory_centroids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    directory_path TEXT NOT NULL,     -- relative to workspace root
    topics_json TEXT,                 -- JSON array of topic strings
    entities_json TEXT,               -- JSON array of entity strings
    timeframe TEXT,
    document_types_json TEXT,         -- JSON array
    confidence REAL NOT NULL DEFAULT 0.0,
    contributing_files INTEGER NOT NULL DEFAULT 0,
    virtual_members INTEGER NOT NULL DEFAULT 0,
    last_updated INTEGER NOT NULL,
    UNIQUE(workspace_path, directory_path)
);

CREATE INDEX IF NOT EXISTS idx_dc_workspace ON directory_centroids(workspace_path);
CREATE INDEX IF NOT EXISTS idx_dc_confidence ON directory_centroids(confidence);

-- Stores per-file enrichment signatures for centroid computation
CREATE TABLE IF NOT EXISTS file_enrichment_signatures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,          -- relative to workspace root
    topics_json TEXT,                 -- JSON array
    entities_json TEXT,               -- JSON array
    document_type TEXT,
    timeframe TEXT,
    enrichment_source TEXT,           -- "companion", "lucene-index", "filename-heuristic"
    last_enriched INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path)
);

CREATE INDEX IF NOT EXISTS idx_fes_workspace ON file_enrichment_signatures(workspace_path);
CREATE INDEX IF NOT EXISTS idx_fes_directory ON file_enrichment_signatures(
    workspace_path,
    file_path  -- for directory prefix queries
);
