# Knowledge Integrity: Understanding and Managing Synthesis Trust

## Context

Use this skill when:
- Evaluating how much to trust a skill's content before acting on it
- Understanding why agents sometimes get faster but less complete answers with skills
- Designing new skills or updating existing ones
- Working on `synthesis verify`, `synthesis gaps`, or confidence metadata (issues #93–#95, #100–#102)

---

## The Core Problem: Documentation Trust Bias

Agents loaded with skills exhibit a **documentation trust bias**: they accept skill content at
face value before verifying against source. This is rational — skills are in context before any
tool call happens — but it means stale skills actively mislead faster than no skills at all.

**Benchmark evidence (P5-A1, Feb 19, 2026):**
- Skill claimed 3 Lucene boost fields. Source (`SearchIndex.java`) had 6.
- Knowledge agents: 8 tool calls (read skill → believed it → verified later)
- Baseline agents: 5 tool calls (went straight to source, found complete truth)
- The skill was the bottleneck, not the help.

> *"Synthesis is currently an amplifier with no feedback loop. When it surfaces a skill
> to an agent, it implicitly vouches for that content."* — Opus analysis, Feb 19, 2026

---

## Three Failure Modes of Knowledge Infrastructure

### 1. Stale Knowledge
Skill contains facts that were once true but are now outdated.

**Signature:** Skill says X, source now says X + more (or X changed entirely).
**Example:** Skill documented 3 boost fields; SearchIndex.java has had 6 for months.
**Risk:** Agent acts on partial data confidently without knowing it's partial.
**Mitigation:** `synthesis verify` (#93) — cross-reference skills against source, flag drift.

### 2. Silent Knowledge
No skill was wrong. A pattern simply exists in code but nobody documented it.

**Signature:** Answer is technically correct but misses important coupling/behavior.
**Example:** The `config`/`core` circular dependency (P5-R2). `RelateCommand` being used
as a shared utility by 3 non-CLI packages. Neither was in any skill.
**Risk:** Agent gives "clean" answer that omits an architectural smell — engineering
decisions get made on incomplete maps.
**Mitigation:** `synthesis gaps` (#94) — identify undocumented high-complexity source files.

### 3. Ambiguous Knowledge
Observation documented, but intent/context missing.

**Signature:** Same structural fact, completely different engineering implication.
**Example (P4-B1):**
- Baseline/CLI: "V7 migration is missing — sequence goes V6 then V8."
- Knowledge: "V7 is intentionally reserved." (from CLAUDE.md Known Gotchas)
Same observation. One sounds like a bug. One is a design decision.
**Risk:** Without the why, correct-looking answers drive wrong conclusions.
**Mitigation:** Document intent explicitly. "X is [absent/empty/missing] INTENTIONALLY because..."

---

## Three Dimensions of Answer Quality (Beyond Correctness)

The Phase 5 benchmark revealed that correctness is not enough — all 90 sessions scored 3/3
but produced qualitatively different answers:

### Currency
*Is the data current?*

E1 (ROI metrics) produced three "correct" answers from three different dates:
- Knowledge: Feb 17 data (36,342 files indexed)
- CLI: Feb 14 data (8,934 files, from MEMORY.md)
- Baseline: Real inconsistency found between files (1.1% vs 2.7%)

All 3/3. Only one was current. The skill answer was freshest; the MEMORY answer was stale.

### Depth
*Is the answer complete, or just the clean version?*

P5-R2 (module dependency graph):
- Knowledge (15 calls): Clean 4-layer narrative. Fast. Missed circular dependency.
- Baseline (32 calls): Found `config`/`core` circular coupling docs didn't mention.
- CLI (37 calls): Found `RelateCommand` hidden utility — an architectural smell pointing
  to real refactoring need.

All 3/3. Each "correct" at a different depth. For architectural decisions, shallow = dangerous.

### Semantic Richness
*Does the answer capture intent, or just structure?*

"V7 is missing" (observation) ≠ "V7 is intentionally reserved" (intent).
Same data point, opposite engineering implication.

---

## How to Evaluate Skill Trustworthiness

Before acting on a skill's technical claims, ask:

| Signal | What to check | How |
|---|---|---|
| **Age** | When was skill last updated vs source last changed? | `git log` on both files |
| **Specificity** | Does it claim specific values (counts, fields, algorithms)? | Verify these against source |
| **Completeness cue** | Does it say "3 fields" or "including, but not limited to"? | Trust partial claims less |
| **Intent markers** | Does it explain *why*, not just *what*? | "intentionally" = higher trust |
| **Source references** | Does it cite a specific file/class/method? | Verify reference still matches |

**Rule of thumb:** Skills are most reliable for architecture, patterns, and intent.
They're least reliable for specific counts, enum values, and configuration defaults.
When precision matters, verify against source.

---

## Tiered Skill Architecture (Planned: #96)

Current problem: All 15 CLI guide skills are loaded flat, adding +11.2% overhead vs Baseline
because agents read 15 reference files before deciding whether to use search.

**Proposed hierarchy:**

| Tier | Always loaded? | Examples | Size guideline |
|---|---|---|---|
| **Architecture** | ✅ Yes | module structure, key patterns, gotchas | Up to 300 lines each |
| **Routing** | ✅ Yes, small | task-shape → strategy mappings | Max 50 lines total |
| **Reference** | ❌ On demand | CLI guides, API docs, specs | Unlimited, pulled by need |

Architecture and routing skills give ~-30% to -47% efficiency gains.
Reference skills loaded flat give +11% overhead. Only load them when routing says they're needed.

**See `synthesis-task-routing.md`** for the routing tier content.

---

## Knowledge Confidence Infrastructure Roadmap

Three planned features to address the trust calibration problem:

### `synthesis verify` (#93)
Cross-reference skills against source. Output: drift report with confidence signals.
```
DRIFT DETECTED:
- synthesis-agent-patterns.md (modified: Feb 1)
  Claims: 3 search boost fields
  SearchIndex.java (modified: Feb 15): 6 fields found
  Confidence: LOW — skill is 14 days behind source changes
```

### `synthesis gaps` (#94)
Identify high-priority source files with no skill coverage.
```
UNDOCUMENTED (high priority):
- RelateCommand.java — imported by 3 non-CLI packages, no skill covers it
- config/core circular coupling — 12 cross-imports, not in any architecture skill
```

### Confidence metadata on search results (#95, superseded by #102)
Every surfaced knowledge artifact includes:
- Freshness: days since last verified against source
- Coverage: % of related source files addressed by the skill
- Consistency: does it contradict other skills or source?

Planned as part of unified knowledge graph (#100 → #101 → #102).

---

## Unified Knowledge Graph (#100–#102)

The architectural direction: add documentation nodes to the same graph as code nodes.

```
SearchIndex.java ──[documented-by]──> synthesis-agent-patterns.md
                 ──[drift-days]────> 14 → CAUTION
                 ──[coverage]──────> partial (3/6 fields described)
```

- **#100:** Foundation — `knowledge_edges` DB table, entity extraction from skills
- **#101:** Maintain cycle reconciliation — every maintain run re-checks affected skills
- **#102:** Unified response enrichment — search returns code + docs + confidence together

The "pre-warmed, always in-sync" guarantee: every maintain run reconciles the knowledge graph.
Agents always receive confidence scores computed against the latest source state.

---

## Market Position

> *"The market for faster search is crowded. The market for trustworthy AI context is empty."*
> — Opus analysis, Feb 19, 2026

Current framing (retrieval): *"Index everything so agents start with context."*
New framing (integrity): *"Tell teams what they know, what they don't, and where what they
think they know is wrong."*

The buyer shifts: developer (wants speed) → engineering manager (responsible for AI output quality,
has no audit mechanism today).

---

## Related

- `synthesis-task-routing.md` — routing tier: when each condition wins
- `synthesis-agent-patterns.md` — patterns for using synthesis effectively (includes stale skill lesson)
- `synthesis-benchmark.md` — full benchmark history and findings
- GitHub issues: #93 (verify), #94 (gaps), #95/#102 (confidence metadata), #96 (tiered skills),
  #100 (knowledge graph), #101 (reconciliation), #103–#108 (code graph enrichments)

---

*Created: February 19, 2026*
*Source: Phase 5 benchmark findings + Opus analysis (Feb 19, 2026 session)*
*"The session that reframed Synthesis from retrieval tool to knowledge integrity system"*
