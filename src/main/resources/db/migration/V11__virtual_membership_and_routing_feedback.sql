-- V11__virtual_membership_and_routing_feedback.sql
-- Phase 3: Virtual membership links and routing feedback for learning

-- Virtual membership links between files and directories
CREATE TABLE IF NOT EXISTS virtual_memberships (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,           -- the file (physical home elsewhere)
    directory_path TEXT NOT NULL,       -- the directory that has virtual membership
    relationship TEXT,                  -- e.g. "methodology application"
    bid_strength REAL NOT NULL,        -- how strongly the directory wanted this file
    created_at INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path, directory_path)
);

CREATE INDEX IF NOT EXISTS idx_vm_workspace ON virtual_memberships(workspace_path);
CREATE INDEX IF NOT EXISTS idx_vm_file ON virtual_memberships(file_path);
CREATE INDEX IF NOT EXISTS idx_vm_directory ON virtual_memberships(directory_path);

-- Routing feedback: accepted/rejected routing decisions
CREATE TABLE IF NOT EXISTS routing_feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,
    proposed_destination TEXT NOT NULL,
    actual_destination TEXT,            -- null if rejected
    accepted INTEGER NOT NULL,          -- 1 or 0
    confidence_delta REAL,              -- how this feedback adjusts confidence
    timestamp INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rf_workspace ON routing_feedback(workspace_path);
CREATE INDEX IF NOT EXISTS idx_rf_file ON routing_feedback(file_path);
