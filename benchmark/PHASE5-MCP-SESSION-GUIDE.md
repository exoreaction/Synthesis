# Phase 5 MCP Condition: Session Guide

**Issue:** #97
**Status:** Requires 9 interactive Claude Code sessions (MCP not available in Task subagents)
**Condition:** Synthesis MCP + Knowledge skills (NO CLI guide skills) — agent uses `tools/list` self-description

---

## Pre-Session Setup

```bash
# 1. Verify MCP tools load in interactive session
# Open Claude Code in /src/exoreaction/Synthesis — MCP servers load automatically
# Type: "What MCP tools are available?" — should list 8 synthesis tools

# 2. Confirm knowledge skills are in context
# The session system prompt must include the 14 Knowledge skill files
# (NOT the 15 CLI guide skills — MCP condition excludes those)

# 3. Record session ID immediately after opening
ls -t ~/.claude/projects/-src-exoreaction-Synthesis/*.jsonl | head -1
```

**Condition configuration:**
- CLAUDE.md: ✓ (standard project context)
- Knowledge skills (14): ✓ `synthesis-agent-patterns.md`, `synthesis-development.md`, etc.
- Synthesis CLI skills (15): ✗ **EXCLUDED** — MCP's `tools/list` replaces them
- MCP tools: ✓ `search`, `relate`, `graph`, `stats`, `ask`, `explain`, `enrich`, `summary`

---

## The 9 Task Prompts (copy-paste into fresh sessions)

Each task = one fresh interactive session. Record JSONL path before starting.

---

### Task 1: B3 — Cross-repo dependencies

```
How many external repositories does Synthesis depend on, and what are the most
critical cross-repo dependencies? Focus on direct dependencies (not transitive).
```

**Ground truth:** 58 repos, 429 dependencies. Most critical: lib-pcb, Cantara, Quadim.
**Expected MCP tool:** `graph --cross-repo`
**Expected calls:** 2-3 (vs Baseline: 9)

---

### Task 2: E1 — ROI metrics

```
What are Synthesis's validated performance metrics? Include: files indexed per
second, search latency, number of files indexed in validation, retrieval time
reduction percentage, and the date these metrics were last validated.
```

**Ground truth:** 200-300 files/sec, 0.4s search, 36,342 files, 92-95% reduction, Feb 17 2026.
**Expected MCP tool:** MEMORY.md in context (1 call) or `search "ROI metrics"`
**Expected calls:** 1 (vs Baseline: 6)
**Currency test:** Does agent report Feb 17 or Feb 14 data?

---

### Task 3: C2 — Anchor document

```
What is the purpose of the isAnchorDoc field in Synthesis, and which code implements it?
Show the specific class, method, and the logic used to determine if a document is an anchor.
```

**Ground truth:** `BusinessDocumentFinder.isAnchorDoc()` — checks file type + name patterns for ACTIVITY-LOG.md, PIPELINE-STATUS.md, etc.
**Expected MCP tool:** `search "isAnchorDoc"` → `relate BusinessDocumentFinder.java`
**Expected calls:** 2-3 (vs Baseline: 4)

---

### Task 4: P4-C1 — --since flow

```
Trace the complete execution flow of `synthesis changelog --since 7d`. Start from
the CLI entry point, show how "7d" is parsed, how the time window is used to filter
changes, and what data sources are queried.
```

**Ground truth:** `ChangelogCommand` → `ChangedCommand.parseSince()` → `SnapshotManager.getChangesForWorkspace()` → SQLite `change_events` table.
**Expected MCP tool:** `search "--since"` + `relate ChangelogCommand.java`
**Expected calls:** 4-5 (vs Baseline: 6)

---

### Task 5: P4-F2 — Pilot approval flow

```
Describe the complete approval workflow in Synthesis — how does an admin approve
a pilot user request? Show the command, the service class, and the database operation.
```

**Ground truth:** `synthesis pilot approve <email>` → `PilotCommand` → `ApprovalService.approve()` → `pilot_users` table INSERT.
**Expected MCP tool:** `search "ApprovalService"` → `relate ApprovalService.java`
**Expected calls:** 2-3 (vs Baseline: 5)

---

### Task 6: P4-B1 — Flyway migrations

```
List all Flyway migration files in Synthesis, in order. Are there any gaps in the
version sequence? If so, explain why.
```

**Ground truth:** V1-V6, V8, V9 (V7 intentionally absent/reserved — documented in CLAUDE.md Known Gotchas).
**Expected MCP tool:** `search "V7"` or direct from knowledge skills
**Expected calls:** 3-4 (vs Baseline: 8)
**Semantic test:** Does agent say "intentionally reserved" (3) or just "V7 is missing" (1)?

---

### Task 7: P5-R1 — SearchIndex callers

```
Which production Java classes call SearchIndex.search() or SearchIndex.openReadOnly()?
Give a complete list organized by package.
```

**Ground truth:** 24-27 classes. CLI (20), MCP (1), LSP (2), search (1), ai (2), validate (1).
**Expected MCP tool:** `relate SearchIndex.java`
**Expected calls:** 1-2 (vs Baseline: 5)

---

### Task 8: P5-R2 — Module dependency graph

```
Describe the complete module dependency structure of Synthesis. Which packages depend
on which? Are there any circular dependencies or architectural violations?
```

**Ground truth:** 5-layer architecture. One violation: `ai` → `cli` (CodeExplainer → RelateCommand).
**Expected MCP tool:** `graph --modules`
**Expected calls:** 1-2 (vs Baseline: 32)
**Depth test:** Does agent find the `ai→cli` violation (Depth=3) or only clean layer narrative (Depth=1)?

---

### Task 9: P5-A1 — Search quality/boost fields

```
What are the Lucene field names and boost weights used in Synthesis search?
List each field, its boost factor, and what aspect of a document it captures.
```

**Ground truth:** 6 fields: `fileName` (2.5x), `headings` (2.5x), `summary` (1.5x), `content` (1.0x), `fileType` (1.5x), `language` (1.2x).
**Expected MCP tool:** `ask "search boost fields"` or `search "FIELD_BOOSTS"`
**Expected calls:** 2-3 (vs Baseline: 5)

---

## Scoring Template

After each session, fill in:

| Task | Tool calls | Structural (0-3) | Currency (0-3) | Depth (0-3) | Semantic (0-3) | Total (0-12) |
|---|---|---|---|---|---|---|
| B3 | | | | | | |
| E1 | | | | | | |
| C2 | | | | | | |
| P4-C1 | | | | | | |
| P4-F2 | | | | | | |
| P4-B1 | | | | | | |
| P5-R1 | | | | | | |
| P5-R2 | | | | | | |
| P5-A1 | | | | | | |
| **Average** | | | | | | |

## Extracting Metrics After Sessions

```bash
# Find session JSONL files (most recent 9)
ls -t ~/.claude/projects/-src-exoreaction-Synthesis/*.jsonl | head -9

# Count tool calls per session
python3 -c "
import json, sys
with open(sys.argv[1]) as f:
    data = [json.loads(l) for l in f if l.strip()]
tools = [m for m in data if m.get('type') == 'assistant'
         and any(c.get('type') == 'tool_use'
                 for c in m.get('message',{}).get('content',[]))]
print('Tool calls:', len(tools))
" <session.jsonl>
```

## Research Question Answer

**Does MCP's `tools/list` self-description replace CLI guide skills?**

If MCP condition ≥ CLI condition (9.9 avg tool calls) → schemas are sufficient, guide skills add no value.
If MCP condition < Baseline (8.9 avg tool calls) → MCP wins the efficiency benchmark.
If MCP condition matches Knowledge (7.6 avg) → combining knowledge skills + MCP tools is optimal.

The key comparison: MCP (knowledge + MCP tools, no CLI guides) vs CLI (knowledge + CLI guides + CLI tools).
