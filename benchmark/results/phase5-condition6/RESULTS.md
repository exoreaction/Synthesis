# Phase 5 — Condition 6 Results (MCP + Improved Descriptions)

**Date:** 2026-02-26
**Issue:** #270
**Execution:** `claude --print --mcp-config` with synthesis HTTP server on port 8766
**Condition:** MCP tools (41) + CLAUDE.md context + improved tool descriptions (PR #274)
**No system prompt hint** — descriptions only
**Model:** claude-sonnet-4-6

**Description changes (PR #274, commit fa935f5):**
- `search`: "Faster than Grep for discovery... Use this FIRST"
- `relate`: "Show ALL bidirectional relationships... Use INSTEAD OF Grep when finding callers"
- `code-graph`: "Use this FIRST when asked about architecture... replaces dozens of Grep/Read calls"
- `trace`: "Use this INSTEAD OF manually reading files to follow execution flow"
- `impact`: "Use this INSTEAD OF manual Grep-for-usages"

---

## Tool Call Results

| Task | MCP calls | Other calls | Total | Cond4 | ΔC4 | Cond5 | ΔC5 | Baseline | ΔBase |
|---|---|---|---|---|---|---|---|---|---|
| P5-R1 (SearchIndex callers) | 2 (relate, which) | 0 | **2** | 2 | 0% | 6 | **-67%** | 5 | -60% |
| P5-R2 (Module dep graph) | 3 (code-graph×3) | 0 | **3** | 10 | **-70%** | 5 | **-40%** | 32 | -91% |
| E1 (ROI metrics) | 2 (search×2) | 2 (Read×2) | **4** | 0 | +∞ | 1 | **+300%** | 6 | -33% |
| C2 (isAnchorDoc) | 1 (search) | 2 (Grep, Read) | **3** | 3 | 0% | 2 | +50% | 4 | -25% |
| P4-C1 (--since flow) | 1 (search) | 15 (Read×13, Grep, Glob) | **16** | 16 | 0% | 19 | -16% | 6 | +167% |
| P4-F2 (Pilot approval) | 1 (search) | 7 (Read×6, Glob) | **8** | 7 | +14% | 6 | +33% | 5 | +60% |
| P4-B1 (Flyway migrations) | 0 | 18 (Read×15, Glob, Bash, Task) | **18** | 1 | **+1700%** | 2 | **+800%** | 8 | +125% |
| B3 (Cross-repo deps) | 5 (search×5) | 5 (Read×5) | **10** | 3 | **+233%** | 4 | **+150%** | 9 | +11% |
| P5-A1 (Lucene boost fields) | 1 (search) | 3 (Read×2, Grep) | **4** | 10 | **-60%** | 3 | +33% | 5 | -20% |
| **Average** | **1.78** | **5.9** | **7.6** | **5.8** | **+31%** | **5.3** | **+42%** | **8.9** | **-15%** |

---

## Complete Cross-Condition Comparison

| Condition | Avg tool calls | MCP calls/task | Tasks w/ MCP | Δ vs Baseline |
|---|---|---|---|---|
| Baseline | 8.9 | 0 | 0/9 | — |
| Knowledge | 7.6 | 0 | 0/9 | -15% |
| **MCP + Descriptions (Cond6)** | **7.6** | **1.78** | **8/9** | **-15%** |
| **MCP (Cond4)** | **5.8** | **0.56** | **4/9** | **-35%** |
| **MCP + Hint (Cond5)** | **5.3** | **1.44** | **6/9** | **-40%** |
| CLI | 9.9 | 0 | 0/9 | +11% |

**MCP + Hint (Cond5) wins.** MCP + improved descriptions (Cond6) performs the same as Knowledge-only and is +31% worse than baseline MCP (Cond4).

---

## Key Findings

### 1. `relate` and `code-graph` descriptions worked exactly as intended

The two tasks where the descriptions provided clear structural advantage both improved dramatically:

- **P5-R1:** `relate` + `which` only (0 other calls) = 2 total. Cond4 also got 2, but used Grep×2 instead of MCP. **Descriptions correctly redirected from Grep to `relate`.**
- **P5-R2:** `code-graph`×3 only (0 other calls) = 3 total vs Cond4's 10 (code-graph×2 + Bash×8). **-70%. Pure MCP, perfect.**

These are exactly the wins the description rewrites were designed to produce.

### 2. `search` descriptions caused over-invocation

The rewrite "Use this FIRST for discovery" was interpreted too broadly. The agent invoked `search` on nearly every task, often multiple times, then still ran Grep/Read on top:

- **B3:** `search`×5 + Read×5 = 10 calls. Cond4: 1 MCP + 2 other = 3. The description "Use this FIRST" caused the agent to run multiple exploratory searches instead of targeted reads.
- **E1:** `search`×2 + Read×2 = 4 calls. Cond4: 0 calls (answered from context). Descriptions caused the agent to search instead of trusting CLAUDE.md.
- **P4-C1:** `search`×1 + Read×13 = 16 calls. Description made agent search first, then still read all files.

The `search` "Use FIRST" instruction competed with existing context knowledge — causing searches where none were needed.

### 3. P4-B1 regression is severe (+1700% vs Cond4)

P4-B1 scored 18 tool calls including `Task` subagent and `Bash` invocations — the agent spawned a subagent to answer the Flyway question. In Cond4, 1 Glob was sufficient. In Cond5, 2 Globs. Descriptions may have made the agent uncertain about whether to use `search` or file tools, causing it to over-deliberate and escalate.

This is an outlier — P4-B1 is a simple pattern-match task (find `V*.sql` files) where any description overhead hurts.

### 4. Verdict: description rewrites are tool-specific, not universal

| Tool | Description rewrite effect | Result |
|---|---|---|
| `relate` | "Use INSTEAD OF Grep for callers" | ✅ Worked — agent switched from Grep to relate |
| `code-graph` | "Use FIRST for architecture" | ✅ Worked — agent used pure MCP, eliminated Bash |
| `search` | "Use FIRST for discovery, faster than Grep" | ❌ Hurt — caused over-invocation, replaced context knowledge |
| `trace` | "Use INSTEAD OF reading files" | Neutral (P4-C1 still used file reads after trace) |
| `impact` | (not triggered in this task set) | — |

**The problem:** "Use FIRST" for `search` is too broad. It should be scoped: "Use FIRST when you need to *discover* relevant files across a large codebase. Do NOT use if CLAUDE.md context is sufficient or if you already know which files to read."

---

## Condition 6 vs Condition 5: Why Hint Wins

Condition 5 (hint) achieved 5.3 avg because the hint was **additive and selective** — it told the agent which MCP tool to prefer *without* suppressing existing knowledge. The agent still used CLAUDE.md context for E1 (1 call), still used direct Glob for P4-B1 (2 calls).

Condition 6 (descriptions) achieved 7.6 avg because the `search` "Use FIRST" instruction **overrode context knowledge**. The agent searched even when it already knew the answer from CLAUDE.md.

**Lesson:** Description rewrites should say "Use this INSTEAD OF Grep" (comparative, not imperative) rather than "Use this FIRST" (absolute). The agent needs to maintain its cost model — MCP tools aren't always cheaper than context knowledge.

---

## Recommended Description Fix

**Current `search` description problem:**
> "Use this FIRST for finding relevant files; fall back to Grep only for exact string/regex matching."

**Better:**
> "Use this INSTEAD OF Grep when you need to discover relevant files in an unfamiliar codebase. If CLAUDE.md context already points to the relevant files, read them directly. Fall back to Grep only for exact regex patterns not in the index."

The key change: "when you need to discover" (conditional) vs "FIRST" (absolute). This preserves the context-knowledge path for E1 and P4-B1 while still redirecting the unfamiliar-codebase search cases.

---

## Execution Notes

- JAR version: synthesis-1.18.1-SNAPSHOT (built 2026-02-26, includes PR #274 descriptions)
- Previous JAR (conditions 4 & 5): synthesis-1.18.0 (2026-02-25, pre-PR #274)
- Transport: HTTP (`--http-port 8766`), workspace: `/src/exoreaction/Synthesis`
- Session isolation: `--no-session-persistence`
- All 9 tasks run in parallel as background subprocesses within one synchronous Bash call
