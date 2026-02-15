-- Synthesis Metrics Database - Initial Schema
-- Version: 1.0
-- Created: 2026-02-15

-- Main metrics table for MCP tool invocations and performance tracking
CREATE TABLE IF NOT EXISTS metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    mcp_tool TEXT,
    mcp_workspace TEXT,
    execution_time_ms INTEGER,
    result_count INTEGER,
    success INTEGER NOT NULL,
    error_message TEXT,
    search_pattern TEXT,
    ai_feature TEXT,
    ai_tokens_used INTEGER,
    ai_retry INTEGER
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_timestamp ON metrics(timestamp);
CREATE INDEX IF NOT EXISTS idx_mcp_tool ON metrics(mcp_tool);
CREATE INDEX IF NOT EXISTS idx_workspace ON metrics(mcp_workspace);
CREATE INDEX IF NOT EXISTS idx_event_type ON metrics(event_type);

-- Metadata table for storing configuration
-- Note: Schema version is tracked by Flyway in flyway_schema_history table
CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Store initial schema version (using INSERT OR REPLACE for backward compatibility)
INSERT OR REPLACE INTO metadata (key, value) VALUES ('schema_version', '1');
