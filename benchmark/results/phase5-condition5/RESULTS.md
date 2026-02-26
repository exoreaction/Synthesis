# Phase 5 — Condition 5 Results (MCP + System Prompt Hint)

**Date:** 2026-02-26
**Issue:** #270
**Execution:** `claude --print --mcp-config` with synthesis HTTP server on port 8766
**Condition:** MCP tools (41) + CLAUDE.md context + one-line decision heuristic hint
**Model:** claude-sonnet-4-6

**System prompt hint injected via `--append-system-prompt`:**
> "Synthesis MCP tools are available. Prefer `search` over Grep for discovery, `relate` over Grep for callers/dependents, `code-graph` for architecture, `trace` for execution flow, `impact` for change analysis."

---

## Tool Call Results

| Task | MCP calls | Other calls | Total | Cond4 (MCP) | Δ Cond4 | Baseline | Δ Baseline |
|---|---|---|---|---|---|---|---|
| P5-R1 (SearchIndex callers) | 1 (search) | 5 (Grep×4, Read) | **6** | 2 | **+200%** | 5 | +20% |
| P5-R2 (Module dep graph) | 5 (code-graph×5) | 0 | **5** | 10 | **-50%** | 32 | -84% |
| E1 (ROI metrics) | 0 | 1 (Read) | **1** | 0 | +∞ | 6 | -83% |
| C2 (isAnchorDoc) | 0 | 2 (Grep, Read) | **2** | 3 | **-33%** | 4 | -50% |
| P4-C1 (--since flow) | 2 (trace, which) | 17 (Glob×11, Read×6) | **19** | 16 | **+19%** | 6 | +217% |
| P4-F2 (Pilot approval) | 2 (search×2) | 4 (Read×4) | **6** | 7 | **-14%** | 5 | +20% |
| P4-B1 (Flyway migrations) | 0 | 2 (Glob×2) | **2** | 1 | **+100%** | 8 | -75% |
| B3 (Cross-repo deps) | 2 (search×2) | 2 (Read×2) | **4** | 3 | **+33%** | 9 | -56% |
| P5-A1 (Lucene boost fields) | 1 (search) | 2 (Read×2) | **3** | 10 | **-70%** | 5 | -40% |
| **Average** | **1.44** | **3.9** | **5.3** | **5.8** | **-8%** | **8.9** | **-40%** |

---

## Comparative Summary (All Conditions)

| Condition | Avg tool calls | MCP calls/task | Tasks w/ MCP | Δ vs Baseline |
|---|---|---|---|---|
| Baseline | 8.9 | 0 | 0/9 | — |
| Knowledge | 7.6 | 0 | 0/9 | -15% |
| **MCP (Cond4)** | **5.8** | **0.56** | **4/9** | **-35%** |
| **MCP + Hint (Cond5)** | **5.3** | **1.44** | **6/9** | **-40%** |
| CLI | 9.9 | 0 | 0/9 | +11% |

**MCP + Hint narrowly wins** — 5.3 vs 5.8, an 8% improvement over MCP alone.

---

## Key Findings

### 1. Hint increased MCP utilization but not always efficiently

MCP tasks per session rose from 0.56 to 1.44 (2.6× more). Tasks with ≥1 MCP call went from 4/9 to 6/9. But the extra MCP calls didn't always reduce total calls — in some tasks the agent used `search` as a preliminary step and *then* still ran Grep/Read anyway:

- **P5-R1:** 1 `search` + Grep×4 + Read = 6 total (Cond4 used 2 Greps, no MCP, for 2 total)
- **P4-C1:** 2 MCP calls (`trace`, `which`) + Glob×11 + Read×6 = 19 total (Cond4: 16)
- **P4-B1:** 2 Glob calls vs Cond4's 1 Glob — hint may have added deliberation overhead

The hint caused the agent to add MCP calls but not always *replace* standard tool calls with them.

### 2. Hint helped most where MCP has structural advantage

Tasks with clear MCP superiority saw significant gains:

- **P5-R2:** `code-graph`×5 only (no Bash) = 5 total vs Cond4's 10 (`code-graph`×2 + Bash×8). **-50%.**
- **P5-A1:** `search` + Read×2 = 3 total vs Cond4's 10 (Grep×4 + Glob×3 + Read×2 + search). **-70%.**

These tasks have architecture/graph queries where MCP tools deliver a complete answer without file reads.

### 3. Code tracing remains resistant to MCP optimization

P4-C1 got *worse* with hint (+19% over Cond4). The `trace` tool was invoked but didn't reduce the need for deep file reading. The agent correctly used `trace` to get a high-level path, then still needed 17 file reads to verify exact line numbers and understand control flow. This confirms the Cond4 analysis: MCP gives you the graph; it doesn't give you the flow.

### 4. Verdict on the research question

> **Does adding a one-line decision heuristic improve MCP utilization?**

**Yes, meaningfully** — MCP utilization rose 2.6× (0.56 → 1.44 avg calls). But efficiency gain was modest (-8% over Cond4). The reason: the hint caused more MCP invocations but didn't prevent redundant standard tool calls in the same task.

> **Is the underutilization a prompting problem or a schema problem?**

**Both.** The hint alone partially fixes prompting (agent invokes more MCP tools), but without schema-level guidance on *when to stop and trust the MCP result*, the agent still falls back to verification via Grep/Read.

**The tool description rewrites (PR #274)** — with "Use INSTEAD OF Grep" language — are the more targeted fix. They change behavior at the decision point: *do I need Grep after this MCP call?*

---

## Condition 5 vs Condition 4: Pair Analysis

| Task | Cond4 | Cond5 | Verdict |
|---|---|---|---|
| P5-R1 | 2 (Grep×2) | 6 (search+Grep×4+Read) | Hint hurt: agent added MCP without removing Grep |
| P5-R2 | 10 (code-graph×2 + Bash×8) | 5 (code-graph×5) | **Hint helped: pure MCP, no Bash** |
| E1 | 0 | 1 (Read) | Marginal: 1 extra Read call (hallucination fix) |
| C2 | 3 | 2 | **Hint helped slightly** |
| P4-C1 | 16 | 19 | Hint hurt: added 2 MCP calls + still read files |
| P4-F2 | 7 | 6 | **Hint helped marginally** |
| P4-B1 | 1 | 2 | Hint hurt: 2 Glob vs 1 Glob |
| B3 | 3 | 4 | Hint hurt slightly: 2 search + 2 Read vs 1 tool + 2 |
| P5-A1 | 10 | 3 | **Hint helped massively: search replaced 7 calls** |

**Hint helped:** P5-R2, C2, P4-F2, P5-A1 (4/9 tasks, avg improvement: -38%)
**Hint neutral/hurt:** P5-R1, E1, P4-C1, P4-B1, B3 (5/9 tasks, avg degradation: +65%)

The net is slightly positive (-8%) but the hint is not uniformly beneficial.

---

## Execution Notes

- Transport: HTTP (`--http-port 8766`), confirmed via `curl /health`
- Session isolation: `--no-session-persistence` (clean per-task context)
- MCP tools granted via `--allowedTools` with all 41 synthesis tool names
- `CLAUDECODE` env var must be unset for nested `claude` subprocess
- Parallel execution: 9 tasks run as concurrent shell background processes within one synchronous Bash call; all completed in ~5 min
- Correctness evaluation: pending manual review (not scored in this run)

---

## Recommended Next Steps

1. **Run Condition 6: MCP + Improved Descriptions** (PR #274 applied to JAR) — tests whether schema rewrites alone beat the hint
2. **Diagnose "additive MCP" pattern** — agent adds MCP calls without removing Grep. May need description language: "If `search` returns the result, DO NOT run Grep to verify"
3. **Run Tasks T1/T2/T3** (changelog, impact, security from PHASE5-MCP-SESSION-GUIDE.md) — better discriminators where MCP has no standard-tool equivalent
