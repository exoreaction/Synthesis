# Feature: `synthesis sessions` -- Episodic Memory (Claude Code Session History)

**Status:** Implemented (v1.21.0)
**Migration:** V18 (`claude_sessions` + FTS5 + triggers)
**Tests:** 20 (8 scanner + 12 store)

---

## Overview

AI-augmented development generates two distinct categories of knowledge: workspace artifacts (files, code, documents) and conversation transcripts (session history). Synthesis has always indexed the first category. The sessions module indexes the second.

Every Claude Code session produces a JSONL transcript in `~/.claude/projects/`. These transcripts contain design decisions, rejected approaches, debugging rationale, and context that never reaches committed code. Without indexing, this knowledge is effectively write-only -- you generated it but cannot retrieve it.

The sessions module makes Claude Code session history searchable as **episodic memory**, completing Layer 2 of a three-layer AI memory model.

## Three-Layer AI Memory Model

| Layer | Type | Source | Synthesis Command |
|-------|------|--------|-------------------|
| 1 | Working memory | Context window | (current conversation) |
| 2 | Episodic memory | Session transcripts | `synthesis sessions` |
| 3 | Semantic memory | Workspace knowledge graph | `synthesis search`, `relate`, `impact` |

Layer 1 is ephemeral -- it exists only during a conversation. Layer 3 is structural -- it captures what exists in the workspace. Layer 2 bridges the gap: it captures what was discussed, decided, and attempted across all past sessions, regardless of whether those decisions materialized into files.

## Architecture

### Data Flow

```
~/.claude/projects/**/*.jsonl    (source: Claude Code session transcripts)
        |
        v
ClaudeSessionScanner             (streaming JSONL parser, incremental)
        |
        v
SessionStore                     (SQLite DAO, synchronized)
        |
        v
claude_sessions table            (10 columns)
claude_sessions_fts              (FTS5 virtual table over first_message + all_user_text)
```

### Storage Design

Session data lives in the global Synthesis database, not in any workspace-specific `.synthesis/` directory. This is deliberate: sessions span projects and are associated with the user, not a workspace.

The V18 Flyway migration creates:
- `claude_sessions` -- primary table with session_id (PK), project_dir, started_at, ended_at, turn_count, tool_call_count, tool_names, first_message, all_user_text, scanned_at
- `claude_sessions_fts` -- FTS5 virtual table indexing first_message and all_user_text
- Three triggers (INSERT, UPDATE, DELETE) that keep the FTS index synchronized automatically

### Incremental Scanning

`ClaudeSessionScanner` compares each JSONL file's `lastModified` timestamp against the `scanned_at` value stored in the database. Files that have not changed since the last scan are skipped entirely. On first scan, 2,971 sessions were indexed in 109 seconds. Subsequent scans process only new or modified files and complete near-instantly.

### Concurrency

All `SessionStore` public methods are `synchronized`. This prevents write conflicts when the CLI and MCP server access the store concurrently from different threads.

## CLI Reference

```bash
# Scan and index session history (incremental)
synthesis sessions scan

# Full-text search across all sessions
synthesis sessions search "authentication"
synthesis sessions search "database migration"

# List recent sessions (default limit: 10)
synthesis sessions list

# Filter by project directory
synthesis sessions list --project my-project

# Filter by time window
synthesis sessions list --since 7d
synthesis sessions list --since 24h

# Get full detail for a specific session
synthesis sessions get abc123-def456
```

### Output Format

Each session listing shows: session ID, project directory, start time, turn count, tool call count, tool names used, and the first user message (as an intent signal). The `search` subcommand additionally shows matching text excerpts ranked by FTS5 relevance.

## MCP Tool Reference

Tool name: `sessions`

```json
{
  "action": "search",
  "query": "authentication refactoring",
  "limit": 10
}
```

```json
{
  "action": "list",
  "project": "my-project",
  "since": "7d",
  "limit": 5
}
```

Parameters:
- `action` (required): `"search"` or `"list"`
- `query` (required for search): FTS5 query string
- `project` (optional, for list): filter by project directory substring
- `since` (optional, for list): time window (e.g., `"7d"`, `"24h"`)
- `limit` (optional): maximum results, default 10

The `sessions` MCP tool runs within the same Synthesis MCP server process that serves workspace knowledge. No second MCP server is required.

## Design Decisions

**Standalone module vs. Lucene pipeline.** Session transcripts are not workspace artifacts. They live in a global location (`~/.claude/`), are not associated with any single workspace, and have different search semantics (temporal filtering, project scoping). SQLite + FTS5 was chosen over Lucene for this data: simpler schema, native temporal queries, and the relational model naturally fits session metadata with a full-text overlay.

**FTS5 over Lucene.** For session search, FTS5 provides sufficient ranking quality. Sessions are searched by keyword and phrase, not by the complex field-boosted queries that Lucene handles for workspace files. FTS5 also integrates cleanly with the existing SQLite infrastructure.

**allUserText capping.** Each user turn contributes up to 1,000 characters to the `all_user_text` field. This keeps the FTS index manageable while preserving the essential keywords and intent from each turn. Very long code pastes are truncated, but natural language content is captured in full.

## Known Characteristics

- Sessions are ephemeral conversation records, not workspace artifacts. They do not appear in `synthesis search` results.
- Very old sessions may have been pruned by Claude Code itself. Synthesis indexes whatever JSONL files exist at scan time.
- The `first_message` field serves as an intent signal -- it captures what the user was trying to accomplish at the start of the session.
- Large JSONL files (multi-hour sessions) stream without issue. The scanner processes files line-by-line, not by loading entire files into memory.
