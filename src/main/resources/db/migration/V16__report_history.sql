-- V16__report_history.sql
-- Tracks when each (target, topic) report combination was last generated.
-- Enables "since last report" default period when no -p flag is provided.
-- See: https://github.com/exoreaction/Synthesis/issues/250

CREATE TABLE IF NOT EXISTS report_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target TEXT NOT NULL,
    topic TEXT NOT NULL,
    generated_at TEXT NOT NULL,
    period_days INTEGER NOT NULL,
    source_documents INTEGER,
    output_file TEXT,
    UNIQUE(target, topic)
);

CREATE INDEX IF NOT EXISTS idx_report_history_target_topic
    ON report_history(target, topic);
