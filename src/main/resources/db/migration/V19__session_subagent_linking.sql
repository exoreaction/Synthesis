-- Parent-child linking for subagent sessions.
--
-- Claude Code writes subagent transcripts to:
--   ~/.claude/projects/<project>/<session-uuid>/subagents/agent-<agent-id>.jsonl
--
-- These JSONL files contain isSidechain=true and a sessionId field that
-- references the PARENT session UUID. This migration adds columns to
-- track that relationship.

ALTER TABLE claude_sessions ADD COLUMN parent_session_id TEXT;
ALTER TABLE claude_sessions ADD COLUMN agent_id TEXT;
ALTER TABLE claude_sessions ADD COLUMN is_subagent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claude_sessions ADD COLUMN agent_slug TEXT;

CREATE INDEX IF NOT EXISTS idx_sessions_parent
    ON claude_sessions(parent_session_id);
CREATE INDEX IF NOT EXISTS idx_sessions_subagent
    ON claude_sessions(is_subagent);
