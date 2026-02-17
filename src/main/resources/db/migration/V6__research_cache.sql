-- Research cache for deep research reports (v1.7.0+)
--
-- Stores expensive multi-pass research reports to avoid regeneration.
-- Cache entries are keyed by workspace, target, topic, passes, and index fingerprint.
-- Invalidation happens automatically when the index changes (new fingerprint).

CREATE TABLE IF NOT EXISTS research_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    -- Cache key components
    workspace_path TEXT NOT NULL,
    target TEXT NOT NULL,              -- chatgpt, notebooklm-infographic, notebooklm-presentation
    topic TEXT NOT NULL,               -- full, architecture, security, quality, dependencies, evolution
    passes TEXT NOT NULL,              -- Comma-separated pass names (e.g., "architecture,security,synthesis")
    index_fingerprint TEXT NOT NULL,   -- Hash of index state (file count + last modified)

    -- Cached data
    model TEXT NOT NULL,               -- AI model used (e.g., "claude-sonnet-4-5-20250929")
    report_content TEXT NOT NULL,      -- Rendered final report
    pass_results TEXT NOT NULL,        -- JSON array of ResearchPassResult objects

    -- Cost tracking metadata
    token_count INTEGER,              -- Total tokens across all passes
    estimated_cost_usd REAL,          -- Estimated USD cost

    -- Metadata
    created_at TEXT NOT NULL,
    hits INTEGER NOT NULL DEFAULT 0,  -- Cache hit counter

    -- Unique constraint on cache key
    UNIQUE(workspace_path, target, topic, passes, index_fingerprint)
);

-- Index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_research_cache_lookup
    ON research_cache(workspace_path, target, topic, passes, index_fingerprint);

-- Index for workspace-level operations
CREATE INDEX IF NOT EXISTS idx_research_cache_workspace
    ON research_cache(workspace_path, created_at);
