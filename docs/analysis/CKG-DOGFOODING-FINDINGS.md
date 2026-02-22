# CKG Dogfooding Findings — Synthesis on Itself

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Workspace:** `/src/exoreaction/Synthesis`
**Analyst:** Thor Henning Hetland + Claude

Synthesis running its own Code Knowledge Graph against itself.
Extraction: 501 files, 4,026 dependency edges, 280 cross-format links, 31 packages.

---

## Extraction Stats

```
Files processed:    501
Dependencies found: 4,026
Cross-format links: 280
Packages found:     31
External deps:      3,255
Elapsed:            33.5s
```

---

## Architecture Layer Map

```
Layer 1 — Foundation (instability 0.00-0.25)
  config      fan-in:  8  fan-out: 2   instability: 0.20 ✓
  core        fan-in: 15  fan-out: 2   instability: 0.12 ✓
  db          fan-in:  6  fan-out: 0   instability: 0.00 ✓
  git         fan-in:  1  fan-out: 0   instability: 0.00 ✓
  index       fan-in: 12  fan-out: 3   instability: 0.20 ✓
  metrics     fan-in:  3  fan-out: 0   instability: 0.00 ✓
  util        fan-in: 19  fan-out: 0   instability: 0.00 ✓
  workspace   fan-in:  3  fan-out: 0   instability: 0.00 ✓

Layer 2 — Core Services (instability 0.26-0.50)
  ai          fan-in:  8  fan-out: 5   instability: 0.38 ✓
  analyzer    fan-in:  7  fan-out: 3   instability: 0.30 ✓
  graph       fan-in:  5  fan-out: 3   instability: 0.38 ✓
  insights    fan-in:  3  fan-out: 2   instability: 0.40 ✓
  skills      fan-in:  1  fan-out: 1   instability: 0.50 ✓
  telemetry   fan-in:  2  fan-out: 1   instability: 0.33 ✓
  update      fan-in:  1  fan-out: 1   instability: 0.50 ✓

Layer 3 — Application (instability 0.51-0.75)
  synthesis   fan-in:  3  fan-out: 4   instability: 0.57 ✓
  architecture fan-in: 1  fan-out: 3   instability: 0.75 ⚠
  enrichment  fan-in:  4  fan-out: 6   instability: 0.60 ✓
  org         fan-in:  4  fan-out: 6   instability: 0.60 ✓
  research    fan-in:  1  fan-out: 2   instability: 0.67 ⚠
  search      fan-in:  2  fan-out: 4   instability: 0.67 ⚠
  summary     fan-in:  2  fan-out: 4   instability: 0.67 ⚠
  tracking    fan-in:  1  fan-out: 3   instability: 0.75 ⚠
  validate    fan-in:  1  fan-out: 3   instability: 0.75 ⚠

Layer 4 — Entry/CLI (instability 0.76-1.00)
  changelog   fan-in:  1  fan-out: 4   instability: 0.80 ⚠
  cli         fan-in:  1  fan-out: 25  instability: 0.96 (expected)
  integration fan-in:  1  fan-out: 6   instability: 0.86 ⚠
  lsp         fan-in:  0  fan-out: 4   instability: 1.00 ⚠
  mcp         fan-in:  0  fan-out: 10  instability: 1.00 ⚠
  report      fan-in:  0  fan-out: 4   instability: 1.00 ⚠
  staging     fan-in:  1  fan-out: 6   instability: 0.86 ⚠
```

**Observations:**
- `util` (fan-in: 19, instability: 0.00) — the hidden load-bearer; nothing depends *on it* outward, everything depends *on it* inward. Correct for a utility layer.
- `cli` (128 files, fan-out: 25) — correctly identified as the entry point but massively oversized.
- `lsp`, `mcp`, `report` at 1.00 instability with zero fan-in — pure entry points with no internal callers. Architecturally correct.
- `db` at 0.00 instability and zero fan-out — perfectly stable foundation.

---

## Circular Dependencies (C001) — HIGH

### 1. `config ↔ core` — **3 edges config→core, 10 edges core→config**
- Two foundational Layer 1 packages locked in a circular dependency.
- `core` has 10 imports from `config` (heavier direction); `config` has 3 imports from `core`.
- **Likely cause:** `core` references config types (workspace config, settings) and `config` references core domain types. They grew together without a clear boundary.
- **Fix direction:** Extract shared types (interfaces, records) to a `model` or `domain` package that both can depend on without circular coupling.
- **Note:** C001 health signal reported "30 edges each way" — this is a **bug in the health signal description**. The actual edge counts (3 and 10) come from `--cycles`. The signal is overcounting (may be summing class-level edges rather than package-level).

### 2. `cli ↔ integration` — **4 edges each way**
- CLI commands reaching into `integration` and `integration` reaching back into `cli`.
- **Likely cause:** `integration` was likely meant as an adapter layer but ended up importing CLI types for convenience.
- **Fix direction:** `integration` should depend on `cli` (or vice versa), not both ways. Create a contract interface that `cli` exposes and `integration` implements.
- **Note:** C001 reported "16 edges each way" — same overcounting bug as above. Actual is 4 each way.

---

## Layer Violations (SDP — Stable Dependencies Principle)

| From (stable) | To (unstable) | Issue |
|--------------|---------------|-------|
| `core` (0.12) | `config` (0.20) | Part of config ↔ core circular dep above |
| `index` (0.20) | `analyzer` (0.30) | Stable indexing layer pulling in analyzer |
| `ai` (0.38) | `insights` (0.40) | Minor — similar instability levels |
| `analyzer` (0.30) | `ai` (0.38) | Stable analysis pulling in AI layer |
| `integration` (0.86) | `cli` (0.96) | Part of cli ↔ integration circular dep above |
| `skills` (0.50) | `org` (0.60) | Skills reaching into org layer |

**Most actionable:** `index → analyzer` — the search index package should not depend on the analyzer. Likely the index calls analyzer to process files during indexing. Consider injecting the analyzer as a dependency rather than importing directly.

---

## God Packages (C012) — MEDIUM

| Package | Files | Threshold |
|---------|-------|-----------|
| `cli` | 128 | 15 |
| `org` | 72 | 15 |
| `graph` | 36 | 15 |
| `report` | 21 | 15 |
| `core` | 21 | 15 |
| `analyzer` | 18 | 15 |
| `util` | 20 | 15 |

**Note:** The threshold of 15 may be too low for a mature codebase. `cli` at 128 is genuinely a problem; `util` at 20 is debatable.

**Recommended threshold:** 30 files (or configurable). `cli` at 128 still fires loudly; `util` at 20 is noise.

---

## High Fan-in Packages Without Tests (C010) — MEDIUM

| Package | Fan-in | Risk |
|---------|--------|------|
| `util` | 19 | Highest — used by everything |
| `core` | 15 | High — domain model |
| `index` | 12 | High — search backbone |
| `config` | 8 | Medium |
| `ai` | 8 | Medium |
| `analyzer` | 7 | Medium |
| `db` | 6 | Medium |

**Note:** Tests likely exist but in a flat `test/` structure rather than mirrored package names. The C010 detector checks for a matching test package directory — it may be giving false positives here.

**Action needed:** Verify whether this is a real gap or a false positive from test package detection logic.

---

## Quality Gaps (CKG-3)

`synthesis code-graph gaps` reported: **"No quality gaps detected"**

This is suspicious given C010 fires for missing test coverage. The gap detector and the health signal detector are disagreeing.

**Likely cause:** `QualityGapDetector.MISSING_TESTS` may use different logic than `CodeHealthAnalyzer.C010`. One checks for test package existence, the other may check for test file naming patterns.

**Action needed:** Align MISSING_TESTS gap detection with C010 logic, or document the intentional difference.

---

## Tool Behavior Issues Found

### 1. `describe` requires `--refresh` even after `extract`
After running `code-graph extract`, running `code-graph describe` (without `--refresh`) says:
```
No module profiles found. Run: synthesis code-graph extract && synthesis code-graph describe --refresh
```
The `--refresh` flag should not be required after a fresh extract. Either:
- `extract` should automatically compute profiles, OR
- `describe` should detect no profiles and compute them automatically

### 2. Workspace path sensitivity
`synthesis code-graph extract -d /src/exoreaction` (without `/Synthesis`) returns exit code 2 silently.
The error message is suppressed by `2>/dev/null` but no output goes to stdout either.
**Action needed:** Better error message when workspace has no `.synthesis/` config.

### 3. `inferred_purpose` always "General purpose"
28 out of 31 packages show "General purpose" — the `inferPurpose()` heuristics are too conservative.
Known-good packages like `util`, `config`, `core` should get more specific labels.
Packages that DO get correct labels: `cli` (CLI command implementations), `db` (Data persistence), `ai` (AI integration), `graph` (Graph analysis), `index` (Search and indexing), `org` (Organization and routing).
The 16-heuristic `inferPurpose()` needs tuning for naming patterns like `tracking`, `changelog`, `enrichment`, `summary`.

---

## Positive Findings

- The **4-tier layer grouping is architecturally accurate** — the real entry points (cli, mcp, lsp) are correctly in Layer 4; the real foundations (db, util, core) are correctly in Layer 1.
- **Circular dep detection works correctly** — the two cycles found are real.
- **Layer violation detection works** — the 6 violations found are real SDP issues.
- **Cross-format links: 280** — SQL→Java, YAML→Java links are being tracked.
- **`util` at fan-in 19** is correctly identified as the most widely-used package.

---

## Further Exploration Needed

- [x] Run `--format mermaid` and verify output — **valid Mermaid, paste at mermaid.live** (31 nodes, full edge set)
- [x] Run `synthesis code-graph --cycles` standalone — **correct output, but edge counts differ from C001 health signal (bug)**
- [x] Run `synthesis code-graph --hotspots` — **no hotspots detected (correct: high-instability packages have low fan-in)**
- [x] Investigate the `config ↔ core` circular dep — **root cause identified, see below**
- [x] Check whether C010 false positive — **confirmed false positive, see below**
- [ ] Run on a different codebase (lib-pcb? Quadim?) for comparison
- [x] Verify cross-format link quality — **bug found: target/ dir double-counts links, see below**
- [x] Test `synthesis relate` speed (SQLite vs file scan) — **finding: JVM startup dominates, see below**

---

## Recommended Follow-up Issues

| Priority | Issue | Effort |
|----------|-------|--------|
| HIGH | `describe` should not require `--refresh` after `extract` | Small |
| HIGH | Align MISSING_TESTS gap detection with C010 logic | Small |
| HIGH | **Bug: C001 health signal overcounts edges** — says "30/16 each way", actual is 3+10 / 4+4 | Small |
| MEDIUM | Tune `inferPurpose()` heuristics for more specific labels | Medium |
| MEDIUM | C012 god package threshold should be configurable (suggest 30) | Small |
| LOW | Better error message when workspace has no `.synthesis/` config | Small |
| LOW | Document that `lsp`, `mcp`, `report` at 1.00 instability is expected | Trivial |

---

---

## `config ↔ core` Circular Dep — Root Cause

`SynthesisConfig.java` (in `config`) imports from `core`:
- `Ecosystem` — ecosystem type enum/record
- `EcosystemDetector` — detects project ecosystem
- `SmartExclusions` — exclusion rule engine

These three classes live in `core` but are conceptually configuration-related. They ended up in `core` because `WorkspaceManager` (core) also uses them, creating the cycle.

**Fix direction:** Move `Ecosystem`, `EcosystemDetector`, `SmartExclusions` out of `core` into either `config` or a new `model` package. `core` then depends only on interfaces, not on config-specific types.

---

## C010 False Positive — Confirmed

C010 fired for `util` (fan-in: 19), `core` (fan-in: 15), `index` (fan-in: 12), etc.

**But test packages DO exist:**
- `src/test/java/io/exoreaction/synthesis/util/` — 12 test files
- `src/test/java/io/exoreaction/synthesis/core/` — 11 test files
- `src/test/java/io/exoreaction/synthesis/config/` — 3 test files

**Bug:** `CodeHealthAnalyzer.C010` is checking for a test package using a path pattern that doesn't match the actual test directory structure. It may be looking for test files inside the same source root rather than `src/test/`.

**Action:** Fix C010 to check `src/test/java/` mirror of `src/main/java/` package path.

---

## Cross-Format Links — Double-Counting Bug

`--cross-format` shows 280 links but many appear in pairs:
```
target/classes/db/migration/V1__initial_schema.sql → SynthesisApp.java
src/main/resources/db/migration/V1__initial_schema.sql → SynthesisApp.java
```

The `target/` build directory is being scanned alongside `src/`, creating duplicate cross-format links. The extractor should exclude `target/`, `.git/`, and other non-source directories.

**Action:** Add `target/` to the exclusion list in `CrossFormatLinker` (or inherit from workspace exclusion config).

---

## `synthesis relate` Speed — JVM Startup Dominates

| Mode | Time |
|------|------|
| SQLite-backed (default) | 5.58s |
| File scan (`--refresh`) | 5.77s |

Both are ~5.6s. The SQLite fast path IS working (slight improvement visible), but JVM startup accounts for ~5s of the total. The real speedup would be visible in a warm JVM context (e.g., MCP server, long-running process) or on a much larger codebase.

**`SynthesisConfig.java` relate output is excellent:**
- 11 outgoing references (imports `Ecosystem`, `EcosystemDetector`, `SmartExclusions` from `core`)
- 49 incoming references (used by CLI, core, mcp, staging, search, tests)
- Correctly identifies it as the most widely-used config type in the codebase

---

## Recommended Follow-up Issues (Updated)

| Priority | Issue | Effort |
|----------|-------|--------|
| HIGH | **Bug: C001 overcounts edges** in health signal description | Small |
| HIGH | **Bug: C010 false positive** — test package detection uses wrong path | Small |
| HIGH | **Bug: cross-format links include `target/`** — double-counting | Small |
| HIGH | `describe` should not require `--refresh` after `extract` | Small |
| MEDIUM | Fix `config ↔ core` circular dep — move Ecosystem/EcosystemDetector/SmartExclusions | Medium |
| MEDIUM | Tune `inferPurpose()` heuristics — 28/31 packages show "General purpose" | Medium |
| MEDIUM | C012 god package threshold configurable (suggest 30, not 15) | Small |
| LOW | `relate` JVM startup: consider daemon mode or GraalVM native image for CLI speed | Large |
| LOW | Better error message when workspace has no `.synthesis/` config | Small |

---

*Last updated: February 22, 2026 — ongoing, add findings below as exploration continues*
