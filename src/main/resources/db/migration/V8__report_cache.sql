-- Report cache for business executive reports (v1.8.0+)
--
-- Stores AI-generated business reports to avoid expensive regeneration.
-- Cache entries are keyed by workspace, topic, target, period, and document fingerprint.
-- Invalidation happens automatically when business documents change (new fingerprint).

CREATE TABLE IF NOT EXISTS report_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    -- Cache key components
    workspace_path TEXT NOT NULL,
    topic TEXT NOT NULL,               -- weekly, pipeline, activities, executive, decisions
    target TEXT NOT NULL,              -- ceo, board, investor
    period TEXT NOT NULL,              -- 1w, 2w, 1m
    document_fingerprint TEXT NOT NULL, -- hash of discovered doc paths + mtimes

    -- Cached data
    model TEXT NOT NULL,               -- AI model used (e.g., "claude-sonnet-4-5-20250929")
    report_content TEXT NOT NULL,      -- Rendered final report

    -- Cost tracking metadata
    token_count INTEGER,              -- Total tokens across all passes
    estimated_cost_usd REAL,          -- Estimated USD cost

    -- Metadata
    created_at TEXT NOT NULL,
    hits INTEGER NOT NULL DEFAULT 0,  -- Cache hit counter

    -- Unique constraint on cache key
    UNIQUE(workspace_path, topic, target, period, document_fingerprint)
);

-- Index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_report_cache_lookup
    ON report_cache(workspace_path, topic, target, period, document_fingerprint);
