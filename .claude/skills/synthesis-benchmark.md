# Synthesis Impact Benchmark

## Context

Use this skill when you need to:
- Run the Synthesis impact benchmark
- Understand what the benchmark measures and why
- Interpret benchmark results
- Set up conditions correctly before running

## What the Benchmark Measures

The benchmark quantifies Synthesis's real-world impact on Claude Code sessions by comparing three conditions on the same tasks:

| Condition | Setup | What it tests |
|---|---|---|
| **Baseline** | No Synthesis, no CLAUDE.md | Blind navigation (Glob/Grep/Read only) |
| **Skills-only** | CLAUDE.md + skills, no search | Context pre-loading value |
| **Full** | CLAUDE.md + skills + synthesis search | Combined search + context value |

**Primary metrics:** Input tokens, output tokens, cache tokens, tool call count, wall clock time, correctness (0-3 rubric)

## Critical Setup: Workspace Flag + Two-Paths Warning

**Lesson 1 — from MVP Run 1 (Feb 19, 2026):**

All 10 synthesis search calls in the Full condition returned zero relevant results because agents omitted `-d /src/exoreaction`. Without the flag, synthesis defaults to `~/Documents`.

**Lesson 2 — from B1 failure root cause (Feb 19, 2026):**

**Two different paths — easy to confuse:**
- **Synthesis workspace root:** `/src/exoreaction` ← use this with `-d`
- **Project source tree:** `/home/totto/src/exoreaction/Synthesis/` ← navigate here for reads
- `/home/totto/src/exoreaction/` has NO `.synthesis/` index → using it as `-d` = exit code 1

Agents naturally infer the workspace path from the project path, getting it wrong. Full condition prompts MUST include this explicit note:
```
CRITICAL: The synthesis workspace root is /src/exoreaction (NOT /home/totto/src/exoreaction).
/home/totto/src/exoreaction/ has no .synthesis/ index — do NOT use it as the -d flag.
```

**Always specify the workspace in benchmark task prompts:**
```bash
# For source code tasks:
synthesis search -d /src/exoreaction "query" 2>/dev/null

# For business document tasks:
synthesis search "query" 2>/dev/null  # defaults to ~/Documents

# For cross-workspace tasks:
synthesis search --all "query" 2>/dev/null
```

## Benchmark Location

```
/home/totto/src/exoreaction/Synthesis/benchmark/
├── BENCHMARK-DESIGN.md      # Full task definitions, rubrics, statistical design
├── extract-metrics.sh       # JSONL token/tool extraction script
└── results/
    └── mvp-analysis.md      # MVP Run 1 results and findings (Feb 19, 2026)
```

## The 12 Tasks (MVP uses A1, B1, D1, F1)

| ID | Category | Task | Key ground truth |
|---|---|---|---|
| A1 | Navigation | Find search entry point | SearchCommand.call() → SearchIndex.search() |
| A2 | Navigation | Find retention policy | MaintenanceService, retentionDays=0 = never expire |
| A3 | Navigation | Find MCP registration | SynthesisMcpServer + @Tool annotations |
| B1 | Feature understanding | Explain report engine | 1-pass / 2-pass / 4-pass modes |
| B2 | Feature understanding | Explain _processed suffix | routeTo() copies + renames source *_processed.* |
| B3 | Feature understanding | Cross-workspace deps | synthesis changelog, 58 repos, bi-directional |
| C1 | Cross-file reasoning | Trace search query E2E | SearchCommand → SearchService → IndexService → ResultFormatter |
| C2 | Cross-file reasoning | Skill → code connection | synthesis-report skill → BusinessDocumentFinder.isAnchorDoc() |
| C3 | Cross-file reasoning | Test coverage for retentionDays=0 | MaintenanceServiceTest, < not <= predicate |
| D1 | Bug investigation | Debug staging ingest | maintain ≠ staging; use `staging ingest && staging route && maintain` |
| E1 | Business context | Synthesis ROI | 92-95% retrieval reduction, 4.1M NOK/yr ROI |
| F1 | Mixed | Design stale anchor fix | isAnchorDoc() bypass → stale field + changelog bridge |

## Running the MVP (4 tasks × 3 conditions = 12 sessions)

### 1. Prepare sessions

Each condition needs a fresh Claude Code session (to avoid cache contamination):
- Run Baseline first (no prior context)
- Wait 10 minutes between conditions
- Verify JSONL logs exist: `ls ~/.claude/projects/`

### 2. Task prompts

Use exactly the task prompt from BENCHMARK-DESIGN.md. For Full condition, prepend:

```
FULL CONDITION: You MAY use synthesis search.
For source code: synthesis search -d /src/exoreaction "query" 2>/dev/null
For business docs: synthesis search "query" 2>/dev/null
```

### 3. Extract metrics

```bash
./benchmark/extract-metrics.sh ~/.claude/projects/<hash>/<session-id>.jsonl
```

### 4. Score correctness

Compare answer against rubric in BENCHMARK-DESIGN.md. Score 0-3 per task.

## MVP Results Summary (3 runs, Feb 19, 2026)

| Run | Setup | Avg Δ tokens | Avg Δ tool calls | Notes |
|---|---|---|---|---|
| Run 1 | Missing `-d` flag | -7.9% | +1.2% | 0/10 search hits |
| Run 1b | `-d /src/exoreaction`, inconsistent PATH | -13.6% | -4.2% | Mixed — B1 CLI unavailable |
| Run 2 | `-d /src/exoreaction` + explicit PATH | -13.3% | -9.3% | B1 still failed (wrong path) |

**All 12 Full-condition sessions: 3/3 correctness.** B1 search consistently failed across all 3 runs.

**B1 root cause (resolved):** B1 agent used `/home/totto/src/exoreaction` as `-d` path. That directory has no `.synthesis/` index → exit code 1. Fix: explicit note in prompts about two-paths distinction.

**Key finding:** CLAUDE.md/skills reliably save 12-20% tokens (robust). Working search adds -28-42% tool call reduction (sensitive to correct `-d` path).

## Known Design Flaws (fix before Phase 2)

1. **Wrong workspace in Full prompts** → Missing `-d` (Run 1) or wrong path `/home/totto/src/exoreaction` (B1 failure)
2. **D1/Full prompt revealed the answer** → Confounds token comparison
3. **MEMORY.md in Baseline** → D1/Baseline found answer in MEMORY.md (uncontrolled accelerant)
4. **BENCHMARK-DESIGN.md in source tree** → F1/Full agent read it (contamination)
5. **All tasks too easy for Opus** → All scored 3/3, no differentiation

## Related

- `synthesis-search-workspace` skill — workspace routing table and `-d` flag guide
- GitHub issues: #81 (stale anchor detection), #82 (auto-update activity log), #85 (workspace auto-discovery)
- BENCHMARK-DESIGN.md — full task rubrics and statistical design

---

*Created: February 19, 2026*
*MVP Run 1: 8 sessions, 516,795 tokens, ~$7.75*
