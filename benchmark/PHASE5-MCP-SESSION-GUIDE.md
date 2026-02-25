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
How does Synthesis track file-level dependencies between repositories in a workspace?
What CLI command surfaces cross-repo dependency data, and what scale of cross-repo
tracking has been validated in production use?
```

**Ground truth:** The `cross-repo-deps` command (and `graph --cross-repo`) maps file-level references between all indexed repositories. Validated at 58 repos, 429 cross-repo dependencies in <31 seconds.
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
a pilot user request? Show the command, the service class, and the storage mechanism.
```

**Ground truth:** There is no `PilotCommand` class. Pilot approval is managed via a Slack-based flow: `TelemetryCommand` (subcommand `synthesis telemetry`) → `ApprovalService.isApproved(uuid)` → reads Slack `#synthesis-pilots` channel via Slack API, extracts UUIDs from messages. Status is cached locally to `~/.synthesis/approval-status` (a properties file, not a database table). Cache refreshes every 24 hours. Enforcement is soft (nag message, commands still execute).
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

**Ground truth:** 6 fields: `fileName` (3.0x), `headings` (2.5x), `keywords` (2.0x), `summary` (1.5x), `content` (1.0x), `relativePath` (1.0x).
**Expected MCP tool:** `ask "search boost fields"` or `search "FIELD_BOOSTS"`
**Expected calls:** 2-3 (vs Baseline: 5)

---

## Condition 5: MCP + System Prompt Hint

**Setup:** Same as the MCP condition above, but add this line to the session's CLAUDE.md or project instructions before starting:

> "Synthesis MCP tools are available. Prefer `search` over Grep for discovery, `relate` for callers/dependents, `code-graph` for architecture, `trace` for execution flow, `impact` for change analysis."

**Condition configuration:**
- CLAUDE.md: ✓ (standard project context + one-line decision heuristic above)
- Knowledge skills (14): ✓ `synthesis-agent-patterns.md`, `synthesis-development.md`, etc.
- Synthesis CLI skills (15): ✗ **EXCLUDED**
- MCP tools: ✓ `search`, `relate`, `graph`, `stats`, `ask`, `explain`, `enrich`, `summary`

**Same 9 tasks** — copy-paste the prompts from Tasks 1–9 above into fresh sessions.

**Purpose:** Tests whether MCP underutilization is a prompting problem (fixable with one line) vs a schema problem (requires tool description rewrites, issue #271). If Condition 5 avg tool calls drop from 5.8 to ~4.0, the fix is the system prompt. If not, the tool description rewrites are needed.

---

### Task 10: T1 — Recent changes

```
What has changed in the Synthesis codebase in the last 7 days? Summarize the
most significant modifications.
```

**Ground truth:** Use `changelog --since 7d`. Expected MCP tool: `changelog`. Expected calls: 1-2 (vs Grep alternative: 10+). Note: Answer will vary by run date.

---

### Task 11: T2 — Change impact

```
If I were to refactor SearchIndex.java — changing its public API — which other
files in the codebase would be directly or transitively affected?
```

**Ground truth:** `impact SearchIndex.java` returns the transitive set. Expected MCP tool: `impact`. Expected calls: 1-2 (vs manual Grep+follow: 15+).

---

### Task 12: T3 — Security audit

```
Run a security analysis of the Synthesis codebase. What are the most significant
vulnerability paths or security concerns in the dependency graph?
```

**Ground truth:** `security` command output. Expected MCP tool: `security`. Expected calls: 1-2. Note: Answer depends on current security findings.

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
| T1 | | | | | | |
| T2 | | | | | | |
| T3 | | | | | | |
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

**Does a one-line system prompt hint fix MCP underutilization?**

If Condition 5 avg tool calls ≈ Condition 4 (MCP) → hint makes no difference, schema rewrites needed (issue #271).
If Condition 5 avg tool calls < Condition 4 by ≥1.5 → the hint is the fix, no schema rewrites needed.
If Condition 5 avg tool calls < Baseline but > Condition 4 → partial fix, both hint + schema rewrites are optimal.
