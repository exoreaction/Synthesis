-- Synthesis Metrics Database - Initial Schema (metrics-only)
-- Version: 1.0
-- Created: 2026-02-22
--
-- This migration is separate from SynthesisDatabase migrations.
-- MetricsDatabase only needs the metrics and metadata tables.

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
CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Store initial schema version
INSERT OR REPLACE INTO metadata (key, value) VALUES ('schema_version', '1');
