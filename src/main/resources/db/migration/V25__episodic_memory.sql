-- Episodic memory: hash-pinned, re-verifiable plan/grounded-answer artifacts.
-- Mirrors kcp-agent's memory architecture (#371 item 3):
-- "a memory is a plan you can re-verify against a moved world."
--
-- Content-stripped: never caches unit bytes (prevents bypassing access gates).
-- Hash-addressed: same artifact = same id, so re-recording is idempotent.

CREATE TABLE IF NOT EXISTS memories (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id     TEXT UNIQUE NOT NULL,    -- sha256 of content-stripped artifact
    kind          TEXT NOT NULL,            -- 'plan' | 'grounded-answer'
    task          TEXT NOT NULL,            -- the task this artifact answered/planned
    manifest_source TEXT,                  -- manifest file path (provenance)
    manifest_sha  TEXT,                    -- manifest sha256 (provenance)
    options_key   TEXT,                    -- digest of planner options (capabilities context)
    recorded_at   TEXT NOT NULL,            -- ISO-8601 timestamp
    artifact_json TEXT NOT NULL,            -- content-stripped JSON artifact
    workspace     TEXT                     -- workspace scope (nullable)
);

CREATE INDEX IF NOT EXISTS idx_memories_task ON memories(task);
CREATE INDEX IF NOT EXISTS idx_memories_kind ON memories(kind);
CREATE INDEX IF NOT EXISTS idx_memories_workspace ON memories(workspace);

-- FTS5 for recall by task overlap
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
    memory_id UNINDEXED,
    task,
    workspace,
    content=memories,
    content_rowid=id
);

-- Sync triggers
CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
    INSERT INTO memories_fts(rowid, memory_id, task, workspace)
    VALUES (new.id, new.memory_id, new.task, new.workspace);
END;

CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, memory_id, task, workspace)
    VALUES ('delete', old.id, old.memory_id, old.task, old.workspace);
END;

CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, memory_id, task, workspace)
    VALUES ('delete', old.id, old.memory_id, old.task, old.workspace);
    INSERT INTO memories_fts(rowid, memory_id, task, workspace)
    VALUES (new.id, new.memory_id, new.task, new.workspace);
END;
