# Task-Shape Routing: When Each Approach Wins

## Context

Use this skill when deciding HOW to approach a task involving the Synthesis codebase.
This is a routing tier skill — it should always be loaded so agents start with the right
strategy rather than discovering it through failed attempts.

This taxonomy is empirically derived from 117 benchmark sessions (Phases 3–5, Feb 2026).

---

## The Routing Decision

**Step 1: Classify the task shape.**
**Step 2: Apply the strategy for that shape.**
**Do not read 15 CLI guide files first.**

---

## Task Shape → Strategy Mapping

### "Files are named in skills / I know the class name"
**Condition:** Task refers to a specific named class, method, or file you've seen before.

→ **Strategy: Read directly. Skip search.**
Skills already contain the file location. Read the named file immediately.
Example: "How does `retentionDays=0` work?" → Read `MaintenanceService.java` directly (named in CLAUDE.md).

**Benchmark:** Knowledge -47% vs Baseline on warm tasks. Search adds 0 value here.

---

### "Find all callers / cross-package dependency / who uses X"
**Condition:** Task requires finding references across multiple packages simultaneously.

→ **Strategy: `synthesis search` first.**
```bash
export PATH="$HOME/bin:/home/totto/bin:$PATH"
synthesis search -d /src/exoreaction "ClassName" 2>/dev/null
```
Synthesis searches all packages simultaneously. grep requires knowing where to look.

**Benchmark:** P5-R1 — CLI found 27 callers across 5 packages in 3 calls. Baseline: 5 calls.

---

### "What is the overall architecture / how do modules relate"
**Condition:** Task asks about high-level structure, layers, module relationships.

→ **Strategy: Read architecture skill first, verify against source only if precision matters.**
The architecture skill contains the module map. Use it for overview, then drill into specific
files only for the details the skill doesn't cover.

```
1. Check synthesis-development skill for module structure
2. For circular dependencies / coupling: synthesis graph --modules -d /src/exoreaction
3. For specific coupling: Grep for imports between suspected packages
```

**Benchmark:** P5-R2 — Knowledge won on efficiency (-53%); Baseline was more complete.
For overview: skill first. For architectural integrity: Baseline-style exploration adds depth.

---

### "One specific class / answer in a single named file"
**Condition:** Task is about a specific well-named class you can grep for directly.

→ **Strategy: Glob/Grep → Read. Skip synthesis search.**
```bash
# Grep is faster than synthesis search for a single known identifier
```

**Benchmark:** P5-A1 — Baseline found `SearchIndex.java` in 5 calls. Knowledge: 8 calls
(read stale skill first, then verified). One well-named file = grep wins.

**Warning:** Don't trust skill content for specific numeric values (counts, fields, weights)
without verifying against source. Skills drift. See `synthesis-knowledge-integrity.md`.

---

### "Design a multi-system solution / implement a new feature"
**Condition:** Task requires reading 8+ files to understand context before designing.

→ **Strategy: No search advantage. Explore directly.**
```
1. Read CLAUDE.md Known Gotchas
2. Read synthesis-development skill for architecture entry points
3. Read relevant source files in parallel
4. Grep for interfaces/extension points as needed
```

Search adds navigation value, not reading value. For design tasks, you're reading regardless.

**Benchmark:** F1-type tasks — all conditions performed similarly because reading dominates.

---

### "Business context / ROI / pipeline status / client info"
**Condition:** Task asks about business data, product metrics, client relationships.

→ **Strategy: synthesis search (default workspace, no -d flag).**
```bash
synthesis search "4.1M NOK retrieval" 2>/dev/null
synthesis search "SpareBank 1 pipeline" 2>/dev/null
synthesis search "workshop ROI" 2>/dev/null
```
Default workspace = ~/Documents (business docs). No -d needed.

---

### "Cross-workspace / source + docs together"
**Condition:** Task spans both code and business documentation.

→ **Strategy: `synthesis search --all`**
```bash
synthesis search --all "query" 2>/dev/null
```
Searches all indexed workspaces. E1 (ROI task): only condition where CLI won via cross-workspace.

---

## Quick Reference Table

| Task signal | Strategy | Command pattern |
|---|---|---|
| Named class in skills | Read directly | `Read path/ClassName.java` |
| "Who calls X" / callers | `synthesis search` | `synthesis search -d /src/exoreaction "ClassName"` |
| Architecture overview | Skill first, verify if precise | `synthesis-development` skill → selective reads |
| Single well-named class | Grep → Read | `Glob **/*Name*.java` → `Read` |
| Design / new feature | Direct exploration | CLAUDE.md → skill → source reads |
| Business / ROI / pipeline | `synthesis search` (docs) | `synthesis search "query"` |
| Code + docs together | `synthesis search --all` | `synthesis search --all "query"` |

---

## Why Flat CLI Guide Skills Fail

The Phase 5 benchmark tried loading 15 CLI guide skills (how-to documentation for each command)
alongside architecture skills. Result: **+11.2% overhead vs Baseline** (WORSE, not better).

Why: agents read all 15 reference files before deciding whether to use search. For 8 of 9 tasks,
search wasn't the right tool — the reading overhead exceeded any navigation benefit.

**Lesson:** Don't load reference documentation by default. Use routing rules (this skill) to
decide WHEN to reference CLI details, then consult the specific guide only at that point.

---

## Projected Impact of Tiered Skills (#96)

| Current (flat loading) | Projected (tiered: arch + routing + on-demand reference) |
|---|---|
| CLI: +11.2% vs Baseline | ~-30% vs Baseline |
| Knowledge: -15% | ~-15% (unchanged, already correct tier) |

Tiered loading would make the fixed CLI condition better than Knowledge alone (-30% vs -15%)
because routing gives agents search access on cross-package tasks where it wins.

---

## Related

- `synthesis-agent-patterns.md` — detailed patterns with benchmark evidence
- `synthesis-knowledge-integrity.md` — when to trust skills vs verify against source
- `synthesis-benchmark.md` — full benchmark history
- GitHub: #96 (tiered skill loading), #98 (multi-axis rubric for Phase 6)

---

*Created: February 19, 2026*
*Empirically derived from Phase 5 benchmark (27 sessions, 9 tasks × 3 conditions)*
*This is a routing tier skill — it should always be loaded, not loaded on demand*
