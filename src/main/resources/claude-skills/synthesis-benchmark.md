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

## Phase 3 Results: 3-Condition Comparison (Feb 19, 2026)

39 sessions total (12 Baseline + 12 Skills-only + 13 Full + 2 Full clean re-runs). All 3/3 correctness.

| Condition | Avg tool calls | Δ vs Baseline |
|---|---|---|
| Baseline | 14.2 | — |
| Skills-only | 7.5 | **-47.2%** |
| Full | 9.8 | **-31.3%** |

**Surprise finding: Skills-only outperforms Full on 11/12 tasks.**

CLAUDE.md context tells agents which files to read → eliminates exploration cycles. Synthesis search adds overhead when agents already know the file locations. Search wins on only 1 task: E1 (cross-workspace business docs — source + Documents simultaneously).

**Key Phase 3 findings:**
1. CLAUDE.md/skills are the primary efficiency driver, not search
2. Search adds most value for "cold discovery" tasks where skills don't name the files
3. All 39 sessions: 3/3 correctness — efficiency without correctness loss
4. Clean re-runs of B2/C2 with #86 fixed confirm results (not lock artifacts)

## MVP Results Summary (3 runs, Feb 19, 2026)

| Run | Setup | Avg Δ tokens | Avg Δ tool calls | Notes |
|---|---|---|---|---|
| Run 1 | Missing `-d` flag | -7.9% | +1.2% | 0/10 search hits |
| Run 1b | `-d /src/exoreaction`, inconsistent PATH | -13.6% | -4.2% | Mixed — B1 CLI unavailable |
| Run 2 | `-d /src/exoreaction` + explicit PATH | -13.3% | -9.3% | B1 still failed (wrong path) |

**B1 root cause (resolved):** B1 agent used `/home/totto/src/exoreaction` as `-d` path. That directory has no `.synthesis/` index → exit code 1. Fix: explicit note in prompts about two-paths distinction.

## Phase 3 Status: COMPLETE ✅

## Phase 4 Status: COMPLETE ✅

## Phase 5 Status: MOSTLY COMPLETE ✅ (MCP pending)

**4 clean conditions — each adds exactly one layer:**

| Condition | What it includes | Status |
|---|---|---|
| Baseline | Claude alone | ✅ 9 sessions |
| Knowledge | CLAUDE.md + 12 architecture/knowledge skills (no CLI guides) | ✅ 9 sessions |
| Synthesis CLI | + 15 CLI guide skills + `synthesis search` | ✅ 9 sessions |
| Synthesis MCP | + MCP server only (NO CLI skills — self-describing via tools/list) | ⏳ Pending |

**Key insight from Phase 5:** CLI guide skills contamination confirmed. Clean Knowledge condition shows -15% vs Baseline. CLI condition = +11% WORSE than Baseline (CLI guide overhead outweighs search benefit).

Design doc: `~/Documents/benchmark-phase4/PHASE5-DESIGN.md`
Results: `~/Documents/benchmark-phase4/phase5-results.md`

**Phase 5 headline (27 sessions, 9 tasks × 3 conditions):**

| Condition | Avg tool calls | Δ vs Baseline |
|---|---|---|
| Baseline | 8.9 | — |
| Knowledge | 7.6 | **-15.0%** |
| CLI | 9.9 | **+11.2% (WORSE)** |

**Phase 5 key findings:**
- Knowledge wins: E1 (-83%), P5-R2 (-53%), C2 (-25%) — architecture knowledge helps
- CLI LOSES to Baseline: guide skills add overhead; only P5-R1 benefits from synthesis search (-40%)
- P5-R2 (module dep graph): Baseline=32, Knowledge=15, CLI=37 — biggest Knowledge advantage
- P5-A1 (search quality): Baseline=5, Knowledge=8, CLI=11 — one well-named file = grep wins

**Combined insight (Phases 3 + 4 + 5):**
- Warm tasks (class named in skills): Knowledge wins (-47% Phase 3)
- Cold, single-class: Baseline wins (grep as fast as search)
- Cold, multi-package cross-file: Full/CLI wins occasionally (-29% to -40%)
- Architecture overview tasks: Knowledge wins dramatically (P5-R2: -53%)
- MCP-specific tasks (graph, relate): MCP expected to win by -80 to -95%

## Known Design Flaws

1. ~~**write.lock contention**~~ → **Fixed in 1.10.0 (#86)**
2. ~~**No Skills-only condition**~~ → **Completed in Phase 3**
3. ~~**Tasks biased toward known files**~~ → **Fixed in Phase 4 (cold tasks)**
4. ~~**CLI guide skills contamination**~~ → **Fixed in Phase 5 (split conditions)**
5. **MEMORY.md contamination persists** → E1 Knowledge/CLI = 1 call from context
6. **BENCHMARK-DESIGN.md in source tree** → F1/Full agent read it (contamination)
7. **All tasks easy for Opus** → All 90 sessions: 3/3, no correctness differentiation

## Related

- `synthesis-search-workspace` skill — workspace routing table and `-d` flag guide
- `synthesis-agent-patterns` skill — 8 patterns for agents using synthesis
- GitHub issues: #85 (workspace auto-discovery), #87 (workspace error message), #88 (drift detection)
- BENCHMARK-DESIGN.md — full task rubrics and statistical design
- `benchmark/results/synthesis-benchmark-report.md` — clean final report

---

*Created: February 19, 2026*
*MVP Run 1: 8 sessions, 516,795 tokens, ~$7.75*
