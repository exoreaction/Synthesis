# Synthesis Agent Patterns

## Context

Use this skill when an AI agent (or Claude Code) needs to use Synthesis effectively.
These patterns were validated by the Synthesis Impact Benchmark (Feb 19, 2026) across
25 sessions measuring tool call reduction. Following them gives ~40% fewer tool calls
on navigation and feature-understanding tasks.

---

## Pattern 1: Always Set PATH First

Synthesis may not be on PATH in spawned agents or subshells.

```bash
export PATH="$HOME/bin:/home/totto/bin:$PATH"

# Verify before using:
which synthesis && synthesis --version 2>/dev/null | head -1
```

Without this, synthesis calls silently fall through to "command not found" and the
agent wastes calls trying to use a tool that isn't visible.

---

## Pattern 2: Always Use the `-d` Flag for Source Code

Without `-d`, synthesis defaults to `~/Documents` (business docs) — not the source workspace.

```bash
# Source code tasks:
synthesis search -d /src/exoreaction "SearchCommand" 2>/dev/null

# Business docs:
synthesis search "pipeline status" 2>/dev/null

# Everything:
synthesis search --all "anchor document" 2>/dev/null
```

**Benchmark evidence:** 10/10 search calls failed in the first run because agents omitted `-d`.

---

## Pattern 3: The Two-Paths Trap

The workspace root and the project source tree live at different paths:

| Path | Has `.synthesis/` | Use for |
|---|---|---|
| `/src/exoreaction` | ✅ YES | `-d` flag in `synthesis search` |
| `/home/totto/src/exoreaction/` | ❌ NO | File reads and edits (Read, Glob, Grep) |

**Common mistake:** Agent navigates to `/home/totto/src/exoreaction/Synthesis/` for file
reads, then infers the workspace path as `/home/totto/src/exoreaction/` → exit code 1.

**Fix:** The `-d` path is always `/src/exoreaction` regardless of what path you're reading
files from.

---

## Pattern 4: Search Term Precision Matters

Specific, code-facing terms outperform natural-language descriptions:

| Task | Weak query | Strong query |
|---|---|---|
| Find TTL=0 behavior | `"never expire"` (20 results, no hit) | `"zero TTL"` (SummaryCacheTest at #5) |
| Find report mode logic | `"report passes"` | `"ReportEngine report passes"` |
| Find MCP registration | `"tool registration"` | `"handleToolsList"` |
| Find retention logic | `"retention policy"` | `"retentionDays"` |

**Rule:** Use identifiers (class names, method names, config keys) rather than descriptions.
The Lucene index boosts `filename` (3x) and `keywords` (2x) — code terms score high.

---

## Pattern 5: Read Related Files in Parallel After a Search Hit

Once synthesis finds a file, you know what other files to read. Don't read them sequentially —
fetch all related files in parallel.

```
# Efficient pattern:
1. synthesis search → finds SearchCommand.java
2. Parallel reads: SearchCommand.java + SearchIndex.java + SynthesisApp.java
   (not sequential: read SearchCommand → find references → read SearchIndex → ...)
```

**Benchmark evidence:** C1/Full (E2E trace task) found all core files via 4 searches,
then parallelized 6 reads simultaneously. 13 calls vs 15 Baseline (-13.3%).

---

## Pattern 6: Sequential Searches Avoid Lock Contention

Lucene's IndexWriter holds an exclusive `write.lock`. Multiple simultaneous `synthesis search`
calls from parallel agents will fail with "lock held by another program" (exit code 1, no results).

```bash
# Safe — sequential:
synthesis search -d /src/exoreaction "first query" 2>/dev/null
synthesis search -d /src/exoreaction "second query" 2>/dev/null

# Risky — parallel (in background or concurrent agents):
# May fail silently — fallback to Glob/Grep if exit code 1
```

**If synthesis search fails unexpectedly, check exit code:**
```bash
synthesis search -d /src/exoreaction "query" 2>/dev/null
echo "exit: $?"  # 0 = success, 1 = lock or workspace error
```

Issue #86 filed for read-only search support. Until fixed, run searches sequentially.

---

## Pattern 7: Graceful Fallback

If synthesis search fails (lock, wrong path, index missing), fall back immediately to
direct navigation — don't retry the same failing call.

```
Search fails?
  → Check exit code
  → If 1: use Glob to find the file by name pattern
  → If index missing: use Grep to search content directly
  → Don't waste calls retrying the same search
```

**Benchmark evidence:** When B2's synthesis searches both hit the lock (0/2), the agent
that fell back to Glob immediately used only 3 additional calls. Agents that retried
synthesis calls wasted 2-3 extra calls.

---

## Pattern 8: Verify Search Works Before Relying On It

In a new session or unfamiliar environment, verify the workspace is indexed before building
a search-heavy strategy:

```bash
export PATH="$HOME/bin:/home/totto/bin:$PATH"
synthesis search -d /src/exoreaction "test" 2>/dev/null | head -3
# "3 results for: test" = working
# "Not a Synthesis workspace" = wrong path
# "" (empty) = index empty, run `synthesis scan -d /src/exoreaction`
# command not found = PATH issue
```

---

## Workspace Routing Quick Reference

| Task | Command |
|---|---|
| Find a Java class | `synthesis search -d /src/exoreaction "ClassName" 2>/dev/null` |
| Find feature implementation | `synthesis search -d /src/exoreaction "featureName" 2>/dev/null` |
| Cross-repo dependencies | `synthesis cross-repo-deps -d /src/exoreaction 2>/dev/null` |
| Architecture graph | `synthesis graph --cross-repo -d /src/exoreaction 2>/dev/null` |
| Business pipeline | `synthesis search "pipeline SpareBank" 2>/dev/null` |
| ROI / product metrics | `synthesis search "4.1M NOK retrieval" 2>/dev/null` |
| Everything | `synthesis search --all "query" 2>/dev/null` |

---

## What Synthesis Is NOT Good For

- **Simple 4-call tasks** — If Baseline only needs 4 calls (Glob + Read), synthesis overhead
  doesn't help. Direct file access is faster for known, simple paths.
- **Complex design tasks (F1-type)** — Search accelerates navigation but doesn't reduce
  reading. You still need to read 8+ files to design a multi-subsystem solution.
- **Parallel agent runs** — Until issue #86 is fixed, parallel synthesis calls compete
  for the lock.

---

## Impact Summary (Synthesis Benchmark, Feb 19, 2026)

| Scenario | Avg Δ tool calls |
|---|---|
| All searches worked (7/12 tasks) | **-39.4%** |
| Mixed success/lock (3/12) | **-29.0%** |
| Fully locked (2/12) | **+26.2%** |
| **Overall average (12 tasks)** | **-31.3%** |

All 25 sessions (Baseline + Full) scored 3/3 correctness. Synthesis improves efficiency,
not just accuracy.

---

*Created: February 19, 2026*
*Based on Synthesis Impact Benchmark Phase 1 + Phase 2 (25 sessions, all 3/3 correctness)*
