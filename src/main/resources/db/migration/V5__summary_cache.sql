-- Summary cache for AI-enhanced summaries (Phase 3)
--
-- Stores generated summaries to avoid expensive regeneration.
-- Cache entries are keyed by workspace, level, perspective, and index fingerprint.
-- Invalidation happens automatically when the index changes.

CREATE TABLE IF NOT EXISTS summary_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    -- Cache key components
    workspace_path TEXT NOT NULL,
    summary_level TEXT NOT NULL,      -- executive, manager, developer
    perspective TEXT NOT NULL,         -- general, executive, architect, etc.
    index_fingerprint TEXT NOT NULL,   -- Hash of index state (file count + last modified)

    -- Cached data (JSON)
    profile_json TEXT NOT NULL,        -- Serialized CodebaseProfile.Profile
    ai_summary TEXT,                   -- AI-generated summary (nullable for --no-ai)

    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    generation_time_ms INTEGER NOT NULL,
    model_used TEXT,                   -- e.g., "claude-sonnet-4-5-20250929"

    -- TTL and invalidation
    expires_at TIMESTAMP,              -- Optional expiration (null = never expires)
    hits INTEGER NOT NULL DEFAULT 0,   -- Cache hit counter

    -- Unique constraint on cache key
    UNIQUE(workspace_path, summary_level, perspective, index_fingerprint)
);

-- Index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_summary_cache_lookup
    ON summary_cache(workspace_path, summary_level, perspective, index_fingerprint);

-- Index for cleanup (finding expired entries)
CREATE INDEX IF NOT EXISTS idx_summary_cache_expires
    ON summary_cache(expires_at) WHERE expires_at IS NOT NULL;

-- Index for cache hit analysis
CREATE INDEX IF NOT EXISTS idx_summary_cache_hits
    ON summary_cache(workspace_path, created_at);
