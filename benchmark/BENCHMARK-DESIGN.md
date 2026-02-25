# Synthesis Impact Benchmark: Complete Design Document

**Version:** 1.0
**Date:** February 19, 2026
**Target codebase:** Synthesis v1.9.4-SNAPSHOT (self-referential)
**Codebase profile:** 48,739 lines Java (main), 26,485 lines Java (test), 2,325 tests, 25 skill files

---

## Quick Summary

| Dimension | Value |
|---|---|
| Tasks | 12 (6 categories) |
| Conditions | 4 (Baseline, Search, Full, MCP + Hint) |
| Replicates (MVP) | 1 → 12 sessions |
| Replicates (full) | 3 → 108 sessions |
| Primary metrics | Tokens (4 types), wall clock, tool calls, correctness (0-3), hallucinations |

**Four conditions:**
1. **Baseline** — Claude Code, no Synthesis, standard tools only
2. **Search** — Claude Code + `synthesis search` MCP tool
3. **Full** — Claude Code + MCP + 46 skill files + full CLAUDE.md
4. **MCP + Hint** — Same as MCP condition but CLAUDE.md includes one decision-heuristic line: "Synthesis MCP tools are available. Prefer `search` over Grep for discovery, `relate` for callers/dependents, `code-graph` for architecture, `trace` for execution flow, `impact` for change analysis."

---

## Why Synthesis as the Target Codebase?

Synthesis is the ideal benchmark target because it contains genuine variation across all asset types:

- **Java source code** — 48,739 lines across CLI, MCP, report engine, indexing, search
- **Test code** — 2,325 tests with domain-specific patterns
- **Skills** — 25 SKILL.md files documenting real domain knowledge
- **Business documents** — ACTIVITY-LOG.md, PIPELINE-STATUS.md, strategy docs
- **Documentation** — Architecture docs, user guides, API references
- **Cross-repo dependencies** — Integrates with lib-pcb, lib-pcb-app, Quadim workspaces

This produces realistic variation in task difficulty, context requirements, and search patterns — unlike a pure-code project like lib-pcb, which lacks the business/documentation layer.

**Self-referential bonus:** Using Synthesis to benchmark Synthesis is a direct dogfooding test. If Synthesis helps with Synthesis tasks, that's compelling.

---

## Hypotheses

| ID | Hypothesis | Expected Effect | Primary Task |
|---|---|---|---|
| H1 | Search reduces context-gathering tokens | 30-60% reduction (Search vs Baseline) | A1-A3 |
| H2 | Full condition reduces total tokens | 40-70% reduction (Full vs Baseline) | All |
| H3 | Search finds relevant files faster | 50-75% fewer tool calls | A1-A3 |
| H4 | Full condition reduces hallucinations | Near-zero vs 1-3 per session (Baseline) | B1-B3 |
| H5 | Full condition reduces wall clock time | 30-50% reduction (Full vs Baseline) | All |
| H6 | Skills prevent pattern-guessing errors | Measurable in correctness scores | B1-B3, F1 |

---

## The 12 Tasks

Tasks are designed with **verified ground truth** from the Synthesis source code, so correctness can be scored objectively (0-3 scale).

### Category A: Navigation Tasks (find specific code)

#### A1 — Find the search entry point
**Prompt:** "Where does `synthesis search` begin execution? Show me the class and method."

**Ground truth:** *(corrected by A1 agents, MVP Run 1)*
- Entry: `SearchCommand.java` → `call()` method
- Delegates to: `SearchIndex.search()` directly (NO SearchService class — it doesn't exist)
- Returns: `SearchResult` list

**Correctness rubric:**
- 0: Wrong class or method
- 1: Correct class, wrong method or misses delegation chain
- 2: Correct class + method, misses that SearchIndex is called directly
- 3: Full chain: SearchCommand.call() → SearchIndex.search() → SearchResult

**Why interesting:** Tests whether agent finds CLI entry point vs index class. Baseline likely Globs for "search" widely; Full condition uses skills.

---

#### A2 — Find retention policy enforcement
**Prompt:** "How does Synthesis decide which indexed files to delete during `maintain`? Show the key logic."

**Ground truth:** *(corrected by A2/Baseline agent, Feb 19)*
- TWO deletion mechanisms (not one):
  1. **Lucene index deletion**: `ScanState.computeChanges()` — presence/absence comparison. File deleted from index if in previous `entries` map but absent from fresh scan. Pure filesystem comparison, no time cutoff.
  2. **Staging file expiry**: `StagingManager.findExpired()` — SQL `expires_at < Instant.now()` (strict `<`). `retentionDays` field controls cutoff via `expiresAt = ingestTime + retentionDays*86400s`.
- `retentionDays=0` → `expiresAt = ingestTime + 0 = ingestTime`. Strict `< now` means it does NOT expire at the same instant. Use `-1` in tests to force expiry.
- **Note: `MaintenanceService` class does not exist.** A2/Baseline agent searched for it and found only `MaintainCommand` (the CLI command).

**Correctness rubric:**
- 0: Wrong class/method
- 1: Finds MaintenanceService but misses retentionDays logic
- 2: Correct logic but misses the `0 = never expire` edge case
- 3: Complete: class + predicate + 0-means-never semantics

---

#### A3 — Locate MCP server registration
**Prompt:** "How are MCP tools registered in Synthesis? Where does the MCP server start?"

**Ground truth:** *(corrected by A3/Baseline agent, Feb 19)*
- `SynthesisMCPServer.java` (note: capital MCPServer, not McpServer) — main entry point with `main()` method
- Tools registered **programmatically** in `handleToolsList()` via `createToolDefinition(name, description, inputSchema)` — NO @Tool annotations (this is plain Java JSON-RPC, not a framework)
- `SynthesisToolHandler.java` — implements actual tool logic, dispatched via switch on tool name
- No `McpCommand.java` — the MCP server is a **separate JAR** (synthesis-mcp-server), not a subcommand of the main CLI
- 8 tools: search, relate, graph, stats, ask, enrich, explain, summary

**Correctness rubric:**
- 0: Cannot find MCP server
- 1: Finds SynthesisMCPServer but says "@Tool annotations" (wrong)
- 2: Finds both SynthesisMCPServer + SynthesisToolHandler, misses "separate JAR" architecture
- 3: Full: SynthesisMCPServer + programmatic registration + SynthesisToolHandler dispatch + separate JAR

---

### Category B: Feature Understanding Tasks (explain how something works)

#### B1 — Explain the multi-pass report engine
**Prompt:** "Explain how `synthesis report` generates output. How many passes does it make and what does each pass do?"

**Ground truth:** *(corrected by B1 agents, MVP Run 1)*
- Pass count depends on topic (NOT always 4):
  - `--topic pipeline/activities/decisions` → 1 pass each
  - `--topic weekly/executive` → 4 passes (pipeline → activities → decisions → executive synthesis)
  - `--product/--client` → 2 passes (evidence → synthesis)
- Each pass feeds output to next; anchor docs bypass period filter
- Output saved to `.synthesis/reports/` for general, or entity directory for product/client

**Correctness rubric:**
- 0: Wrong number of passes or completely wrong description
- 1: Says "always 4 passes" without describing topic-dependent variation
- 2: Gets topic-dependent pass count, misses entity reports (2-pass) or anchor doc behavior
- 3: Full: all 3 pass modes (1/2/4) + what each does + anchor doc behavior

---

#### B2 — Explain the `_processed` suffix behavior
**Prompt:** "What happens to source files when `routeTo()` is called in Synthesis? What is the `_processed` suffix?"

**Ground truth:**
- `routeTo()` copies file to destination path
- Renames source file to `<original-name>_processed.<ext>` (e.g., `invoice.pdf` → `invoice_processed.pdf`)
- This marks source as processed without deleting (safe, auditable)
- Implemented in PR #63

**Correctness rubric:**
- 0: No answer or completely wrong
- 1: Knows about copy but misses rename
- 2: Correct copy + rename, misses `_processed` placement details
- 3: Full: copy + rename + suffix placement + purpose (audit trail)

---

#### B3 — Explain cross-workspace dependency tracking
**Prompt:** "How does Synthesis track dependencies between repositories? What command shows them and what does it output?"

**Ground truth:** *(corrected by B3/Baseline agent, Feb 19)*
- Implemented in `CrossRepoDepsCommand.java` + `GraphBuilder.java`
- Detection: content scanning at query time (regex for Java imports, markdown links, file references). NOT stored in SQLite.
- CLI commands: `synthesis cross-repo-deps` (text output grouped by repo pair) AND `synthesis graph --cross-repo` (visual: Mermaid/DOT/PNG/SVG)
- Bi-directional: "what depends on X" + "what does X depend on"
- Scale: 58 repos, 429 dependencies in <31 seconds (2.3s in benchmark)
- **Note: `synthesis changelog` is NOT the dependency command** — changelog tracks file changes over time, not cross-repo dependencies.

**Correctness rubric:**
- 0: Cannot describe the feature or says wrong command (e.g. "synthesis changelog")
- 1: Finds dependency feature but wrong command or says "stored in SQLite"
- 2: Correct command + bi-directional, misses scale metrics
- 3: Full: correct commands + implementation approach + bi-directional + scale metrics

---

### Category C: Cross-file Reasoning Tasks (connect information across files)

#### C1 — Trace a search query end-to-end
**Prompt:** "Trace what happens when a user runs `synthesis search 'retention policy'`. Follow the code from CLI to index lookup to result formatting."

**Ground truth:** *(corrected by C1/Baseline agent, Feb 19)*
- `SynthesisApp.main()` → picocli routes to `SearchCommand`
- `SearchCommand.call()` → resolves workspace, validates, calls `SearchIndex.search()`
- `SearchIndex.search()` → opens Lucene DirectoryReader, builds MultiFieldQueryParser (6 fields: filename/headings/keywords/summary/content/relativePath with boosts), executes BM25 query
- `SearchIndex.toSearchResult()` → constructs SearchResult records from Lucene Document fields
- `SearchCommand.printResults()` → formats with ANSI colors, summary truncation, metadata
- **Note: SearchService, IndexService, ResultFormatter do NOT exist.** The actual chain skips straight from SearchCommand to SearchIndex.

**Correctness rubric:**
- 0: Cannot trace or completely wrong path
- 1: Gets CLI entry + index class, misses what happens inside each
- 2: Correct chain, misses multi-field boosted query details or ANSI formatting
- 3: Full: SynthesisApp → SearchCommand → SearchIndex → MultiFieldQueryParser → printResults

---

#### C2 — Connect skill to implementation
**Prompt:** "The SKILL.md for `synthesis-report` mentions 'anchor documents'. Find where this concept is implemented in code and explain the implementation."

**Ground truth:**
- Skill mentions anchor docs → code in `BusinessDocumentFinder.isAnchorDoc()`
- `isAnchorDoc()` returns true for ACTIVITY-LOG.md and PIPELINE-STATUS.md
- These bypass the period filter (`parsePeriodCutoff()`)
- `ReportDocument` has `lastModified` field (Instant) but it's not surfaced in output

**Correctness rubric:**
- 0: Cannot connect skill to code
- 1: Finds BusinessDocumentFinder but misses isAnchorDoc
- 2: Finds isAnchorDoc but misses period filter bypass
- 3: Full: BusinessDocumentFinder + isAnchorDoc + period bypass + lastModified gap

---

#### C3 — Explain test coverage for retentionDays=0
**Prompt:** "How does the Synthesis test suite verify that retentionDays=0 means 'never expire'? Find the relevant test(s)."

**Ground truth:** *(corrected by C3/Baseline agent, Feb 19)*
- **`MaintenanceServiceTest.java` does NOT exist.** C3 agent searched and found nothing.
- Test file: `SummaryCacheTest.java` → `cache_withZeroTtl_neverExpires()` (line ~225)
- Mechanism: when `ttlSeconds=0`, `SummaryCache.put()` stores `expires_at = NULL`. The SQL in `get()` uses `expires_at IS NULL OR expires_at > ?` — NULL always passes. `clearExpired()` uses `expires_at < ?` which also skips NULLs.
- For `StagingManager`: tests use `retentionDays=-1` (not 0) to force past-expiry. `retentionDays=0` → `expiresAt=now`, strict `< now` means it never expires.

**Correctness rubric:**
- 0: Cannot find test or finds completely wrong file
- 1: Finds `SummaryCacheTest` but misses the `ttlSeconds=0 → NULL` mechanism
- 2: Correct test + NULL storage, misses the `IS NULL OR >` predicate or staging `-1` pattern
- 3: Full: SummaryCacheTest + NULL mechanism + predicate + staging pattern

---

### Category D: Bug Investigation Tasks (diagnose issues)

#### D1 — Debug staging ingest not triggering
**Prompt:** "A developer runs `synthesis maintain` on the staging workspace but new files from Downloads aren't being processed. Why? What command should they run instead?"

**Ground truth:**
- `maintain` alone ≠ staging ingest + route
- `maintain` only runs maintenance (cleanup, retention)
- Downloads cron needs: `staging ingest && staging route && maintain`
- `staging ingest` moves files from Downloads to staging
- `staging route` routes staged files to destinations

**Correctness rubric:**
- 0: Wrong diagnosis or wrong fix
- 1: Identifies missing staging commands but wrong order
- 2: Correct diagnosis + correct command sequence, misses explanation of each step
- 3: Full: diagnosis + correct sequence + explanation of each step's role

---

### Category E: Business Context Tasks (connect code to business)

#### E1 — Explain Synthesis ROI calculation
**Prompt:** "What ROI does Synthesis claim and where is this validated? Connect the business claims to actual measurements."

**Ground truth (from business docs + code):**
- Claimed: 92-95% retrieval time reduction (5-15 min → 10-30 sec)
- Claimed: 0.4s search, 2.7% storage overhead
- Validated: 8,934 files across 3 workspaces (Feb 14, 2026)
- Business: 4.1M NOK/year ROI for 10-person team
- Source: `.synthesis/reports/`, ACTIVITY-LOG.md, product README

**Correctness rubric:**
- 0: Cannot find claims or cannot connect to validation
- 1: Finds one of: code metrics OR business claims (not both)
- 2: Connects metrics to business claims, misses validation source/date
- 3: Full: metrics + business claims + validation source + date

---

### Category F: Mixed Tasks (require multiple types of reasoning)

#### F1 — Design a fix for stale anchor documents
**Prompt:** "The `synthesis report` command always includes ACTIVITY-LOG.md even if it hasn't been updated in 7 days, producing a stale executive report. Design a fix. Consider what Synthesis already knows about workspace changes."

**Ground truth (from GitHub issues #81 and #82):**
- Problem: `isAnchorDoc()` bypasses period filter → silent staleness
- Fix level 1: Warn in verbose output when anchor doc older than period
- Fix level 2: Detect staleness and use `synthesis changelog` delta to supplement
- Fix level 3: Auto-update ACTIVITY-LOG.md from changelog (issue #82)
- Key: Synthesis watches workspace via `maintain`, has changelog data already

**Correctness rubric:**
- 0: Wrong diagnosis or trivial fix ("just update the file manually")
- 1: Identifies anchor doc bypass as root cause
- 2: Proposes fix level 1 or 2 correctly
- 3: Full: root cause + multi-level fix + recognizes changelog as bridge mechanism

---

## Measurement Protocol

### Primary Metrics

All metrics extracted from Claude Code JSONL session logs at:
`~/.claude/projects/<project-hash>/<session-id>.jsonl`

| Metric | Source | Notes |
|---|---|---|
| Input tokens | `usage.input_tokens` per API call | Aggregate per session |
| Output tokens | `usage.output_tokens` per API call | Aggregate per session |
| Cache write tokens | `usage.cache_creation_input_tokens` | Synthesis-provided context |
| Cache read tokens | `usage.cache_read_input_tokens` | Reused context |
| Wall clock time | First message timestamp → last message timestamp | Session-level |
| Tool calls total | Count of `tool_use` blocks | Aggregate per session |
| Tool calls by name | Breakdown: Glob, Grep, Read, synthesis_search, etc. | Per-tool counts |

### Secondary Metrics (manually scored)

| Metric | Scale | Scorer |
|---|---|---|
| Correctness (structural) | 0-3 (rubric above) | Blind scoring against ground truth |
| Correctness (multi-axis) | 0-12 (4×3, see below) | Post-hoc review |
| Hallucinations | Count of false claims | Post-hoc review |
| Partial credit | Noted for borderline cases | Qualitative annotation |

### Multi-Axis Correctness Rubric (Phase 5+)

**Context:** Phase 5 showed all 27 sessions scored 3/3 on the original 0-3 structural rubric — no
differentiation was possible despite meaningfully different answer quality. The 4-axis rubric
resolves this by separately measuring *what* was found, *how fresh* it was, *how deep* the
analysis went, and *whether engineering intent was understood*.

A perfect answer scores **12/12 (4 axes × 3 points each)**. The original structural score is
preserved as Axis 1 for backward compatibility.

| Axis | 0 | 1 | 2 | 3 |
|---|---|---|---|---|
| **Structural** (original) | Wrong or missing | Partially correct | Mostly correct | Fully correct |
| **Currency** | Wrong data version | Stale (>7 days old) | Recent (<7 days) | Current (same day / exact commit) |
| **Depth** | Surface facts only | Key facts, no patterns | Most patterns found | Anomalies + architectural intent |
| **Semantic** | Structural observation only | Partial intent captured | Clear intent stated | Intent + implications for decisions |

**Scoring guidance:**
- **Structural:** Does the answer contain the correct facts? (same as original rubric)
- **Currency:** Are metrics, file counts, version numbers, dates from the current codebase state?
  - Score 3 = data matches codebase at session date; Score 0 = data from wrong version
- **Depth:** Does the answer go beyond the obvious? Anomalies = things NOT in any skill file
  (e.g., a circular dependency not documented anywhere, an architectural smell)
- **Semantic:** Does the answer explain *why* something is true, not just *that* it is?
  - Example: "V7 is intentionally reserved" (3) vs "V7 is missing" (1) — same observation, different implication

**Phase 5 retroactive scores:**

| Task | Condition | Structural | Currency | Depth | Semantic | Total |
|---|---|---|---|---|---|---|
| P5-R2 (module dep graph) | Baseline | 3 | 3 | 3 | 2 | **11** |
| P5-R2 | Knowledge | 3 | 3 | 1 | 2 | **9** |
| P5-R2 | CLI | 3 | 3 | 3 | 2 | **11** |
| E1 (ROI metrics) | Baseline | 3 | 2 | 2 | 2 | **9** |
| E1 | Knowledge | 3 | 3 | 2 | 2 | **10** |
| E1 | CLI | 3 | 2 | 2 | 2 | **9** |
| P4-B1 (Flyway) | Baseline | 3 | 3 | 2 | 1 | **9** |
| P4-B1 | Knowledge | 3 | 3 | 2 | 3 | **11** |
| P4-B1 | CLI | 3 | 3 | 2 | 1 | **9** |

**Key insight from retroactive scoring:** Knowledge condition wins on Semantic for Flyway task
(CLAUDE.md explains V7 is *intentionally* reserved; other conditions only observe it is missing).
Knowledge condition loses on Depth for P5-R2 (synthesis-development.md provides the clean 4-layer
narrative but agents stop there, missing the `ai→cli` violation and RelateCommand utility smell
that systematic exploration reveals).

**Phase 6+ scoring:** Use the 12-point scale for all tasks. Report both structural (for backward
compatibility with Phase 5 results) and multi-axis scores.

### Correctness Scoring Protocol

1. Run session, save transcript
2. Score blindly (without knowing which condition) if possible
3. Compare against ground truth checklist (above)
4. Record structural score (0-3) + multi-axis score (0-12) + notes
5. For disputed scores: discuss and average
6. Note *which* axis is responsible for score differences — this is the key insight per task

---

## Experimental Design

### Conditions

| Condition | Config | Expected token cost |
|---|---|---|
| **Baseline** | Standard Claude Code, no CLAUDE.md, no MCP | High (blind searching) |
| **Search** | Add `synthesis search` MCP tool only | Medium (targeted search) |
| **Full** | MCP + 25 skills + full CLAUDE.md | Low (pre-loaded context) |
| **MCP + Hint** | MCP + knowledge skills + one-line heuristic in CLAUDE.md | Medium-Low (prompted discovery) |

> **⚠️ Critical — Workspace flag required:**
> For source code tasks, agents MUST specify the workspace: `synthesis search -d /src/exoreaction "query"`
> Without `-d`, synthesis defaults to `~/Documents` (docs workspace) and returns irrelevant results.
> MVP Run 1 (Feb 19) had 0/10 search hits because all calls omitted `-d /src/exoreaction`.
> Issue #85 filed for workspace auto-detection.
>
> **⚠️ Critical — Two different paths (B1 failure root cause):**
> The synthesis workspace root is `/src/exoreaction` (root-level `/src/`).
> The project source code is at `/home/totto/src/exoreaction/Synthesis/` (home-relative).
> These are DIFFERENT directories. `/home/totto/src/exoreaction/` has NO `.synthesis/` index.
> Agents that infer workspace from project path (`-d /home/totto/src/exoreaction`) get exit code 1.
> Full condition prompts MUST include: "CRITICAL: workspace is /src/exoreaction, not /home/totto/src/exoreaction"

### Condition Configuration Scripts

**baseline.sh** — Standard Claude Code session
```bash
#!/bin/bash
# Remove Synthesis MCP and CLAUDE.md for clean baseline
export SYNTHESIS_BENCHMARK_CONDITION=baseline
# Ensure no .mcp.json or CLAUDE.md in session directory
echo "Condition: BASELINE (no Synthesis)"
```

**search.sh** — Search-only condition
```bash
#!/bin/bash
export SYNTHESIS_BENCHMARK_CONDITION=search
# Enable MCP server, disable skill loading
echo "Condition: SEARCH (synthesis search MCP only)"
```

**full.sh** — Full Synthesis condition
```bash
#!/bin/bash
export SYNTHESIS_BENCHMARK_CONDITION=full
# Enable MCP + CLAUDE.md + all skills
echo "Condition: FULL (MCP + skills + CLAUDE.md)"
```

### Cache Isolation Protocol

**Critical:** Claude Code caches context aggressively. Cross-contamination between conditions invalidates results.

Rules:
1. Always run Baseline **first** (no prior context)
2. Wait **10 minutes** between conditions (cache TTL)
3. Use a **fresh session** for each task × condition combination
4. Verify cache read tokens = 0 at session start (or account for pre-existing cache)
5. Note session IDs for each run in the tracking sheet

### Replicate Schedule

**MVP (Phase 1) — 12 sessions (4 tasks × 3 conditions, 1 replicate):**
- Tasks: A1, B1, D1, F1 (one from each main category)
- Sessions: 12
- Estimated time: 2-3 days

**Full study (Phase 2) — 36 sessions (12 tasks × 3 conditions, 1 replicate):**
- All 12 tasks
- Sessions: 36
- Estimated time: 1-2 weeks

**Publication-ready (Phase 3) — 108 sessions (3 replicates):**
- Full study × 3
- Sessions: 108
- Estimated time: 3-4 weeks

---

## Data Collection Sheet

For each session, record:

```
Session ID: ___________________
Date: ___________________
Task: ___________________  (A1/A2/.../F1)
Condition: ___________________  (Baseline/Search/Full)
Replicate: ___________________  (1/2/3)

JSONL file: ~/.claude/projects/<hash>/<session-id>.jsonl

Tokens:
  Input: ___________________
  Output: ___________________
  Cache write: ___________________
  Cache read: ___________________
  TOTAL: ___________________

Wall clock: ___________________ minutes

Tool calls:
  Glob: ___________________
  Grep: ___________________
  Read: ___________________
  synthesis_search: ___________________
  Other: ___________________
  TOTAL: ___________________

Correctness score: ___________________ /3
Hallucinations: ___________________
Notes: ___________________
```

---

## JSONL Metrics Extraction Script

Save as `benchmark/extract-metrics.sh`:

```bash
#!/bin/bash
# extract-metrics.sh <session-jsonl-path>
# Extracts token counts and tool call counts from Claude Code session

SESSION_FILE="$1"
if [ -z "$SESSION_FILE" ]; then
  echo "Usage: $0 <path-to-session.jsonl>"
  exit 1
fi

echo "=== Token Summary ==="
jq -r '
  select(.type == "assistant") |
  .message.usage // empty |
  "Input: \(.input_tokens // 0)  Output: \(.output_tokens // 0)  CacheWrite: \(.cache_creation_input_tokens // 0)  CacheRead: \(.cache_read_input_tokens // 0)"
' "$SESSION_FILE" | \
awk 'BEGIN{i=0;o=0;cw=0;cr=0}
  {match($0,/Input: ([0-9]+)/,a); i+=a[1]
   match($0,/Output: ([0-9]+)/,b); o+=b[1]
   match($0,/CacheWrite: ([0-9]+)/,c); cw+=c[1]
   match($0,/CacheRead: ([0-9]+)/,d); cr+=d[1]}
  END{print "Total Input:", i; print "Total Output:", o;
      print "Cache Write:", cw; print "Cache Read:", cr;
      print "Grand Total:", i+o+cw+cr}'

echo ""
echo "=== Tool Call Summary ==="
jq -r '
  select(.type == "assistant") |
  .message.content[]? |
  select(.type == "tool_use") |
  .name
' "$SESSION_FILE" | sort | uniq -c | sort -rn

echo ""
echo "=== Wall Clock ==="
FIRST=$(jq -r 'select(.timestamp) | .timestamp' "$SESSION_FILE" | head -1)
LAST=$(jq -r 'select(.timestamp) | .timestamp' "$SESSION_FILE" | tail -1)
echo "Start: $FIRST"
echo "End:   $LAST"
```

---

## Statistical Analysis Plan

### Primary Analysis

For each metric (tokens, tool calls, wall clock):

1. **Baseline vs Search:** Wilcoxon signed-rank test (paired, non-parametric)
2. **Baseline vs Full:** Wilcoxon signed-rank test
3. **Effect size:** Cohen's d (or rank-biserial r for non-parametric)
4. **Confidence intervals:** Bootstrap 95% CI on mean reduction %

### Minimum Detectable Effect

With 12 tasks (full study, 1 replicate), power analysis:
- 30% token reduction detectable at α=0.05, power=0.80 with n≥8 tasks
- 12 tasks gives adequate power for H1, H2, H3, H5
- Hallucination rate (H4) needs 3 replicates for reliable estimates (rare events)

### Reporting Format

For each hypothesis:

```
H1: Search reduces context-gathering tokens 30-60% vs Baseline
  Observed: [X]% reduction (Search: [N] tokens, Baseline: [N] tokens)
  p = [p-value], effect size = [d]
  Result: SUPPORTED / PARTIALLY SUPPORTED / NOT SUPPORTED
```

---

## Implementation Phases

### Phase 1: MVP (2-3 days)

**Goal:** Validate measurement infrastructure and get directional signal.

1. **Day 1:** Set up measurement infrastructure
   - Write `extract-metrics.sh`
   - Verify JSONL log location and format
   - Run 1 test session and confirm extraction works

2. **Day 2:** Run 4-task MVP (A1, B1, D1, F1 × 3 conditions = 12 sessions)
   - Morning: Baseline condition (3 sessions)
   - Afternoon: Search condition (after 10-min gap)
   - Evening: Full condition (after 10-min gap)

3. **Day 3:** Score + analyze
   - Score correctness for all 12 sessions
   - Calculate token/tool/clock differences
   - Write brief MVP report

**MVP success criteria:**
- All 12 sessions run without errors
- Metrics successfully extracted
- Directional signal visible (Full < Search < Baseline for tokens)

### Phase 2: Full Study (1-2 weeks)

- All 12 tasks × 3 conditions = 36 sessions
- 1 replicate
- Full statistical analysis

### Phase 3: Publication-Ready (3-4 weeks)

- 3 replicates = 108 sessions
- Full statistical analysis with confidence intervals
- Write-up for blog post / conference talk (JavaZone, NDC, GOTO)

---

## Known Pitfalls & Mitigations

| Pitfall | Risk | Mitigation |
|---|---|---|
| Cache contamination | Baseline inflated by prior sessions | Run Baseline first, 10-min gaps |
| Model version changes | Claude updates mid-study | Lock model version in config |
| Task familiarity | Scorer learns answers across conditions | Blind scoring protocol |
| Ambiguous ground truth | Borderline scores | Pre-defined rubric, dual scoring |
| Session interruptions | Incomplete data | Note in log, exclude from analysis |
| Skill loading overhead | Full condition slower on first call | Warm-up run excluded from metrics |
| Self-referential bias | Synthesis team optimistic | Pre-register hypotheses before running |

---

## Connection to GitHub Issues

This benchmark directly measures the value of:

- **Issue #81** — "feat(report): detect and bridge stale activity log using changelog"
  - Task F1 measures whether agents can diagnose this problem
  - Full condition (with CLAUDE.md knowledge of #81) vs Baseline

- **Issue #82** — "feat(maintain): auto-update activity log from changelog on scheduled runs"
  - Task E1 measures business context comprehension
  - Will re-run after #82 is implemented to measure improvement

---

## Output Artifacts

After Phase 1 (MVP), produce:
- `benchmark/results/mvp-raw.csv` — Raw session data
- `benchmark/results/mvp-analysis.md` — Directional findings

After Phase 2 (Full):
- `benchmark/results/full-raw.csv`
- `benchmark/results/full-analysis.md` — Complete statistical results

After Phase 3 (Publication):
- `benchmark/BENCHMARK-RESULTS.md` — Publication-ready findings
- Blog post draft for JavaZone/NDC submission

---

## Quick Start (Run MVP Today)

```bash
# 1. Find your Claude Code JSONL location
ls ~/.claude/projects/

# 2. Run a test session (Baseline condition, Task A1)
# Prompt: "Where does synthesis search begin execution? Show me the class and method."
# (run in /home/totto/src/exoreaction/Synthesis, no CLAUDE.md, no MCP)

# 3. Extract metrics
./benchmark/extract-metrics.sh ~/.claude/projects/<hash>/<session-id>.jsonl

# 4. Score correctness against rubric (A1 ground truth above)

# 5. Repeat for Search and Full conditions (10-min gap between)

# 6. Compare token counts across conditions
```

---

**Document status:** Design complete, ready for Phase 1 MVP execution
**Next step:** Run `extract-metrics.sh` on a test session to validate JSONL parsing
**Owner:** Thor Henning Hetland
**Repo:** `/home/totto/src/exoreaction/Synthesis/benchmark/`
