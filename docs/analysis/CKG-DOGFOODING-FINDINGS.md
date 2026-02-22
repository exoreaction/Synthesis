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
- [ ] Run `synthesis code-graph --cycles` standalone to verify output format
- [ ] Run `synthesis code-graph --hotspots` to check hotspot detection
- [ ] Investigate the `config ↔ core` circular dep at source level — what classes cause it?
- [ ] Check whether C010 false positive (test package detection) is real
- [ ] Run on a different codebase (lib-pcb? Quadim?) for comparison
- [ ] Verify cross-format link quality — are the 280 links accurate?
- [ ] Test `synthesis relate` speed improvement (SQLite vs file scan)

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

*Last updated: February 22, 2026 — ongoing, add findings below as exploration continues*
