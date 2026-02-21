# Implementation Plan: Filesystem Knowledge Graph Vision

**Date:** 2026-02-21
**Authors:** Claude Opus 4.6 (deep analysis session)
**Based on:** [KNOWLEDGE-GRAPH-VISION.md](../vision/KNOWLEDGE-GRAPH-VISION.md) v1.2, [SYNC-ROUTING-ARCHITECTURE-REPORT.md](SYNC-ROUTING-ARCHITECTURE-REPORT.md)
**Codebase version:** v1.12.2-SNAPSHOT

---

## Executive Summary

This document is a concrete, issue-level implementation plan for realizing the Synthesis Filesystem Knowledge Graph vision across four phases. It translates the north-star vision into work that a solo developer (with AI assistance) can execute incrementally, with each phase delivering standalone value.

**Phase 1 (v1.13.x, ~2 weeks):** Fix the foundation. Repair the transient merge bug, add depth-awareness, unify routing into a single pipeline, and add `synthesis route explain`. No new concepts -- just making the existing heuristic system coherent and debuggable.

**Phase 2 (v1.14.x, ~4 weeks):** Centroid + Wants bootstrap. Connect enrichment to directory identity by computing semantic centroids from enriched files. Introduce cold-start `wants` from directory names. Add `synthesis describe`. This is the conceptual leap -- directories go from passive rule-sets to agents with emerging purpose.

**Phase 3 (v1.15.x, ~4 weeks):** Pull model + Virtual membership. Routing becomes pull-based: directories bid on enriched files. Physical + virtual membership. Want satisfaction metrics. Health signals for starvation and drift. The system starts *learning* from content.

**Phase 4 (v2.0, ~6 weeks):** Full knowledge graph. Aspirational gap detection, want conflict resolution, `synthesis graph` visualization, `synthesis discover` for structural analysis, long-term learning. The north star.

**Total estimated effort:** 16 weeks, ~65 issues, delivered incrementally with each phase shippable independently.

**Critical dependency chain:** Phase 1 is prerequisite for all others. Phase 2 is prerequisite for Phase 3. Phase 3 is prerequisite for Phase 4. Within each phase, issues are ordered by dependency.

---

## Phase 1: Fix the Foundation (v1.13.x)

### Summary

Phase 1 repairs the known design problems documented in the architecture report without introducing any new concepts. The goal is a coherent, debuggable heuristic routing system that can be trusted as a substrate for the semantic features in Phase 2.

**What changes:** Transient merge logic, depth-aware vocabulary, unified routing pipeline, `route explain` command, shared MediaTypes constants.

**Why it matters:** The current system has five fragmented routing mechanisms, a transient flag that can never be cleared, and scores that are meaningless across mechanisms. None of the vision features can be built reliably on this foundation.

**What it unlocks:** A single routing pipeline with transparent scoring, which Phase 2 can extend with centroid-based scoring without needing to touch five different code paths.

### Issues

#### P1-01: Fix transient merge logic (confidence-weighted)

**Description:** Replace the OR-based transient merge in `DirectoryIdentityParser.merge()` line 238 with confidence-weighted logic. When the two sides disagree on transient, the higher-confidence side wins. When confidences are within 0.1 of each other and they disagree, default to `false` (permanent is safer than transient).

**Files:** `DirectoryIdentityParser.java` (line 238)

**Acceptance criteria:**
- A directory with vocabulary confidence 0.6 (transient=true) and signals confidence 0.94 (transient=false) results in transient=false
- A directory with vocabulary confidence 0.6 (transient=true) and signals confidence 0.5 (transient=false) results in transient=true (vocab wins by confidence margin)
- Two sources that agree on transient always produce that value regardless of confidence
- Existing `DirectoryIdentityParserTest` passes; new test cases added for confidence-disagreement scenarios

**Effort:** 0.5 day
**Dependencies:** None
**Risk:** Low -- isolated change in one method

---

#### P1-02: Add depth guard for vocabulary transient

**Description:** After the vocabulary/signals merge in `SyncCommand`, add a depth guard: if the merged result has `transient_=true` and the directory depth from workspace root is > 2, override to `transient_=false`. This is the simpler alternative to changing the vocabulary API.

**Files:** `SyncCommand.java` (after line 222, the merge call)

**Acceptance criteria:**
- `eXOReaction/business/assets/marketing/` (depth 4) is NOT marked transient
- `marketing/` (depth 1) IS still marked transient
- `eXOReaction/marketing/` (depth 2) IS still marked transient
- New test in `SyncCommand` or a new `TransientDepthGuardTest`

**Effort:** 0.5 day
**Dependencies:** P1-01 (merge fix should land first so depth guard is additive)
**Risk:** Very low

---

#### P1-03: Extract MediaTypes shared constants

**Description:** Create `io.exoreaction.synthesis.util.MediaTypes` with the canonical `MEDIA_EXTENSIONS` set. Update all four locations that define media extensions inline: `MaintainCommand`, `E010Check`, `DirectorySignalExtractor`, `MaintainOrchestrator`. Add modern formats: `webp`, `heic`, `m4a`, `wma`, `tiff`.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/util/MediaTypes.java`
- Modified: `MaintainCommand.java`, `E010Check.java`, `DirectorySignalExtractor.java`, `MaintainOrchestrator.java`

**Acceptance criteria:**
- All four locations reference `MediaTypes.MEDIA_EXTENSIONS`
- No inline extension sets remain for media type detection
- `MediaTypes` also provides `VIDEO_EXTENSIONS`, `AUDIO_EXTENSIONS`, `IMAGE_EXTENSIONS` subsets
- All existing tests pass unchanged
- New unit test for `MediaTypes` constants

**Effort:** 0.5 day
**Dependencies:** None
**Risk:** None

---

#### P1-04: Extract shared EXTENSION_REJECT_TYPE_MAP

**Description:** The `EXTENSION_REJECT_TYPE_MAP` is duplicated between `DirectoryScorer.java` and `E010Check.java`. Extract to a shared location (either `MediaTypes` or a new `FileTypeClassification` utility).

**Files:**
- Modified: `DirectoryScorer.java`, `E010Check.java`
- Modified or new: `MediaTypes.java` (or new `FileTypeClassification.java`)

**Acceptance criteria:**
- Single source of truth for extension-to-type mapping
- Both classes reference the shared map
- All existing tests pass

**Effort:** 0.5 day
**Dependencies:** P1-03
**Risk:** None

---

#### P1-05: Retire SubjectBasedRouter -- fold into DirectoryScorer

**Description:** `SubjectBasedRouter` is a simpler version of what `DirectoryScorer` already does (token overlap). Retire it by adding transient-destination filtering to `DirectoryIdentityRouter.discoverCandidates()` and replacing all `SubjectBasedRouter` call sites.

**Files:**
- Modified: `DirectoryIdentityRouter.java` -- add `route(Path file, double threshold, RoutingContext context)` overload with `skipTransient` option
- Modified: `DirectoryIdentityRouter.java` -- `discoverCandidates()` gets optional transient filter
- Modified: `MaintainCommand.java` / `MaintainOrchestrator.java` -- `rebalanceTransient()` calls `DirectoryIdentityRouter` instead
- Modified: `E010Check.java` -- uses `DirectoryIdentityRouter` instead of `SubjectBasedRouter`
- Deleted: `SubjectBasedRouter.java`

**Acceptance criteria:**
- `rebalanceTransient()` uses the unified router with `skipTransient=true`
- `E010Check` uses the unified router
- `SubjectBasedRouter.java` is deleted
- `SubjectBasedRouterTest.java` is converted to test the equivalent behavior via `DirectoryIdentityRouter`
- No per-file workspace walk -- all callers use the cached candidate list
- Score thresholds are recalibrated for `DirectoryScorer` output range (document mapping from old to new thresholds)

**Effort:** 3 days
**Dependencies:** P1-03, P1-04
**Risk:** Medium -- threshold recalibration requires testing with real workspaces. Mitigation: write a comparison test that runs both old and new routers on a test dataset and verifies comparable decisions.

**Design decision -- threshold mapping:**
- `SubjectBasedRouter` threshold 0.4 (E010) --> `DirectoryScorer` threshold ~0.25 (WEAK) -- to be calibrated
- `SubjectBasedRouter` threshold 0.7 (rebalance) --> `DirectoryScorer` threshold ~0.5 (MODERATE) -- to be calibrated
- Decision: calibrate empirically by running both scorers on the test fixtures and logging the distribution

---

#### P1-06: Introduce RoutingContext and RoutingDecision records

**Description:** Create structured records for routing context (caller preferences) and routing decision (result with reasoning). This replaces ad-hoc threshold/flag passing and prepares for the richer decisions in Phase 2+.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/RoutingContext.java`
- New: `src/main/java/io/exoreaction/synthesis/org/RoutingDecision.java`
- New: `src/main/java/io/exoreaction/synthesis/org/RoutingConfidence.java` (enum: CERTAIN, HIGH, MODERATE, LOW, NONE)
- Modified: `DirectoryIdentityRouter.java` -- accept `RoutingContext`, return `RoutingDecision`

**Data model:**
```java
public record RoutingContext(
    double threshold,
    boolean skipTransient,
    boolean mediaOnly,
    boolean dryRun
) {}

public enum RoutingConfidence {
    CERTAIN(0.75),    // Auto-route safe
    HIGH(0.55),       // Single-line confirmation
    MODERATE(0.35),   // Show suggestion with reasoning
    LOW(0.20),        // Mention as possibility
    NONE(0.0);        // No meaningful match
    final double threshold;
}

public record RoutingDecision(
    Path destination,
    double score,
    RoutingConfidence confidence,
    String mechanism,     // "hint", "config-rule", "identity-score"
    List<String> reasons, // human-readable scoring breakdown
    boolean ambiguous
) {}
```

**Acceptance criteria:**
- All routing callers use `RoutingContext` / `RoutingDecision`
- `RoutingConfidence` thresholds are empirically calibrated against test fixtures
- The old `RouteResult` record in `DirectoryIdentityRouter` is replaced by `RoutingDecision`
- Human-readable reasons are always populated

**Effort:** 2 days
**Dependencies:** P1-05
**Risk:** Low -- structural refactoring

---

#### P1-07: Add `synthesis route explain` command

**Description:** A new read-only CLI command that shows how the unified router would score a file against all candidate directories. Shows top 5 candidates with full scoring breakdown, confidence level, and recommendation.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/RouteExplainCommand.java`
- Modified: `SynthesisApp.java` -- register the new subcommand

**CLI surface:**
```bash
synthesis route explain path/to/file.pdf
# Shows: top 5 candidates with scores, confidence levels, reasons
# Shows: recommendation (route/hold/orphan)
# Shows: whether the file is in a transient directory
```

**Acceptance criteria:**
- Command shows top 5 candidates sorted by score
- Each candidate shows: directory path, score, confidence level (CERTAIN/HIGH/MODERATE/LOW/NONE), individual scoring components
- If top two are ambiguous, shows AMBIGUOUS warning
- If file is currently in a transient directory, notes it
- Integration test with a test workspace

**Effort:** 2 days
**Dependencies:** P1-06
**Risk:** Low -- read-only, no side effects

---

#### P1-08: Normalize DirectoryScorer output to 0.0-1.0

**Description:** Currently `DirectoryScorer.totalScore` can exceed 1.0 because scope bonus (up to 0.64) is added on top of content score (up to 1.0). Normalize so that scope bonus acts as a tiebreaker within the 0.0-1.0 range:
`totalScore = contentScore + (scopeBonus * (1.0 - contentScore) * 0.5)`

**Files:** `DirectoryScorer.java`

**Acceptance criteria:**
- No score exceeds 1.0
- Scope bonus still differentiates candidates with identical content scores
- All existing tests updated for the new score range
- `RoutingConfidence` thresholds validated against the new range
- Document the mapping: old score X --> new score Y for reference

**Effort:** 1 day
**Dependencies:** P1-05, P1-06 (should land after router unification and confidence levels)
**Risk:** Medium -- all downstream thresholds need adjustment. Mitigation: do this LAST in Phase 1 after the other changes stabilize.

---

### Phase 1: Key Design Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| Change vocabulary API to accept depth? | No -- use post-merge correction in SyncCommand | Simpler, zero API change, only SyncCommand needs modification |
| Threshold calibration approach? | Empirical: log both old and new scores on test data, pick thresholds that reproduce the same routing decisions | Avoids guessing; we have test fixtures |
| Keep `DirectoryIdentityRouter.RouteResult`? | Replace with `RoutingDecision` | Unified record used everywhere; cleaner API |
| Score normalization timing? | Last in Phase 1 | All other changes should stabilize first; normalization changes all numbers |

### Phase 1: Test Strategy

- **Unit tests:** Each issue has specific tests. P1-01 extends `DirectoryIdentityParserTest` with confidence-disagreement scenarios. P1-05 converts `SubjectBasedRouterTest` to verify equivalent behavior through `DirectoryIdentityRouter`.
- **Integration tests:** P1-07 needs a test workspace fixture with known directories and files to verify `route explain` output.
- **Regression:** Run the full test suite after each issue. Critical: `DirectoryIdentityRouterTest`, `DirectoryScorerTest`, `TransientIdentityTest`, `E010Check` (if tested).
- **Tests that will break:** `SubjectBasedRouterTest` (deleted -- converted). `DirectoryIdentityRouterTest` (signature change). `TransientIdentityTest` (transient logic changes). `DirectoryScorerTest` (score normalization in P1-08).

### Phase 1: Risk Assessment

- **Hardest part:** P1-05 (SubjectBasedRouter retirement) -- requires careful threshold calibration to avoid routing regressions.
- **Scope creep risk:** Adding new scoring signals during router unification. Mitigation: do not add new scoring -- only unify existing mechanisms.
- **Critical path:** P1-01 -> P1-02 -> P1-03/P1-04 -> P1-05 -> P1-06 -> P1-07 -> P1-08.

---

## Phase 2: Centroid + Wants Bootstrap (v1.14.x)

### Summary

Phase 2 introduces the first two layers of the vision `.synthesis.md` format: `centroid:` (what the directory IS) and `wants:` (what the directory is TRYING TO BECOME). It connects enrichment to directory identity for the first time.

**What changes:** Enrichment updates directory centroids. Directories express wants. `synthesis describe` explains what the system understands. Routing uses semantic similarity when enrichment data is available.

**Why it matters:** This is the conceptual leap from "rule-based gatekeepers" to "agents with emerging purpose." For the first time, the system understands what a directory is *about*, not just what file extensions it accepts.

**What it unlocks:** Phase 3's pull model (directories that know their centroid can bid on files). Phase 4's gap detection (centroids make archetype matching possible).

### Data Model Changes

#### New fields in DirectoryIdentity

The `DirectoryIdentity` record grows significantly. Rather than expanding the 14-field record to 25+, introduce a **companion `DirectoryCentroid` record** and a **`DirectoryWants` record** that are stored as nested blocks in `.synthesis.md` but kept as separate objects in memory.

```java
// New record: semantic centroid of a directory
public record DirectoryCentroid(
    List<String> topics,          // e.g. ["renewable energy", "SDD methodology"]
    List<String> entities,        // e.g. ["GreenField Energy", "Jane Smith"]
    String timeframe,             // e.g. "2025-Q4 / 2026-Q1"
    List<String> documentTypes,   // e.g. ["proposal", "contract"]
    double confidence,            // cluster tightness 0.0-1.0
    int contributingFiles,        // count of enriched physical members
    int virtualMembers,           // count of virtual members (Phase 3)
    Instant lastUpdated
) {}

// New record: what the directory wants
public record DirectoryWants(
    List<String> topics,
    List<String> entities,
    List<String> alsoLookingFor,  // aspirational gaps (Phase 4)
    String source,                // "inferred from directory name + 8 files"
    double satisfaction           // 0.0-1.0 want fulfillment (Phase 3)
) {}
```

#### New `.synthesis.md` format blocks

The parser must handle both the legacy format (Phase 1 compatible) and the new format with `centroid:` and `wants:` blocks. The `accepts:` block is preserved for backward compatibility and continues to work -- it becomes a subset of what the `overrides:` block will express.

```yaml
---
synthesis:
  # Legacy fields (preserved, still functional)
  accepts:
    types: ["marketing"]
    formats: ["md", "pdf", "png", "mp4"]
  scope:
    level: "ORGANIZATION"
    organization: "eXOReaction"
  confidence: 0.87
  source: "inferred from directory name + 8 enriched files"

  # NEW: Semantic centroid (system-derived)
  centroid:
    topics:
      - "renewable energy"
      - "SDD methodology"
    entities:
      - "GreenField Energy"
    timeframe: "2025-Q4 / 2026-Q1"
    document_types:
      - "proposal"
      - "contract"
    confidence: 0.87
    contributing_files: 8
    last_updated: "2026-02-21T15:00:00Z"

  # NEW: Wants (cold-start or divergence)
  wants:
    topics: ["GreenField opportunity lifecycle"]
    entities: ["GreenField Energy"]
    source: "inferred from directory name"
    satisfaction: 0.87

  # Existing fields
  transient: false
  last_synced: "2026-02-21T15:00:00Z"
---
```

#### New SQLite tables (Flyway V10)

```sql
-- V10__directory_centroids.sql

-- Stores computed centroid data per directory per workspace
CREATE TABLE IF NOT EXISTS directory_centroids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    directory_path TEXT NOT NULL,     -- relative to workspace root
    topics_json TEXT,                 -- JSON array of topic strings
    entities_json TEXT,               -- JSON array of entity strings
    timeframe TEXT,
    document_types_json TEXT,         -- JSON array
    confidence REAL NOT NULL DEFAULT 0.0,
    contributing_files INTEGER NOT NULL DEFAULT 0,
    virtual_members INTEGER NOT NULL DEFAULT 0,
    last_updated INTEGER NOT NULL,
    UNIQUE(workspace_path, directory_path)
);

CREATE INDEX IF NOT EXISTS idx_dc_workspace ON directory_centroids(workspace_path);
CREATE INDEX IF NOT EXISTS idx_dc_confidence ON directory_centroids(confidence);

-- Stores per-file enrichment signatures for centroid computation
CREATE TABLE IF NOT EXISTS file_enrichment_signatures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,          -- relative to workspace root
    topics_json TEXT,                 -- JSON array
    entities_json TEXT,               -- JSON array
    document_type TEXT,
    timeframe TEXT,
    enrichment_source TEXT,           -- "companion", "lucene-index", "ai-direct"
    last_enriched INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path)
);

CREATE INDEX IF NOT EXISTS idx_fes_workspace ON file_enrichment_signatures(workspace_path);
CREATE INDEX IF NOT EXISTS idx_fes_directory ON file_enrichment_signatures(
    workspace_path,
    file_path  -- for directory prefix queries
);
```

### Issues

#### P2-01: Create DirectoryCentroid and DirectoryWants records

**Description:** Define the two new records in the `org` package. These are pure data classes with no behavior -- behavior comes in subsequent issues.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryCentroid.java`
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryWants.java`

**Acceptance criteria:**
- Both records have `empty()` factory methods
- Both are immutable (Java records)
- Unit tests for construction and empty patterns

**Effort:** 0.5 day
**Dependencies:** None
**Risk:** None

---

#### P2-02: Extend DirectoryIdentityParser for centroid + wants blocks

**Description:** Extend the YAML parser to read and write `centroid:` and `wants:` blocks in `.synthesis.md` files. The parser must handle files that have these blocks AND files that don't (backward compatibility).

**Files:** `DirectoryIdentityParser.java`

**Acceptance criteria:**
- `parse()` returns `DirectoryCentroid` and `DirectoryWants` alongside `DirectoryIdentity` (new return type or composite)
- `write()` emits `centroid:` and `wants:` blocks when present
- Parsing a legacy `.synthesis.md` (no centroid/wants) returns `DirectoryCentroid.empty()` and `DirectoryWants.empty()`
- Round-trip test: write then parse preserves all fields
- Existing parser tests continue to pass

**Design decision -- return type:**
- Option A: Expand `DirectoryIdentity` record to include centroid and wants fields
- Option B: New `DirectoryProfile` record that wraps `DirectoryIdentity` + `DirectoryCentroid` + `DirectoryWants`
- **Recommendation: Option B.** The identity record is already at 14 fields. A wrapper keeps concerns separated and makes the centroid/wants optional without polluting every identity construction site.

```java
public record DirectoryProfile(
    DirectoryIdentity identity,
    DirectoryCentroid centroid,
    DirectoryWants wants
) {
    public static DirectoryProfile fromIdentity(DirectoryIdentity identity) {
        return new DirectoryProfile(identity, DirectoryCentroid.empty(), DirectoryWants.empty());
    }
}
```

**Effort:** 2 days
**Dependencies:** P2-01
**Risk:** Medium -- the line-based YAML parser is fragile. New nested blocks must be parsed carefully. Mitigation: extensive round-trip tests.

---

#### P2-03: Flyway V10 -- directory centroids and enrichment signatures tables

**Description:** Create the Flyway migration for the two new SQLite tables that store centroid data and per-file enrichment signatures.

**Files:**
- New: `src/main/resources/db/migration/V10__directory_centroids.sql`

**Acceptance criteria:**
- Migration runs cleanly on existing databases
- Tables are created with proper indexes
- Integration test creates database, runs Flyway, verifies tables exist

**Effort:** 0.5 day
**Dependencies:** None
**Risk:** Low

---

#### P2-04: Build EnrichmentSignatureExtractor

**Description:** Create a class that extracts a semantic signature (topics, entities, document type, timeframe) from an enriched file. This reads companion `.synthesis.md` files and/or the Lucene index to extract structured enrichment data.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/EnrichmentSignatureExtractor.java`

**Input:** A file path + workspace root + optional `SearchIndex` (read-only)
**Output:** An `EnrichmentSignature` record (topics, entities, documentType, timeframe)

**Extraction strategy (in priority order):**
1. **Companion file:** If `filename.ext.synthesis.md` exists, parse its YAML frontmatter and extract `type`, `Keywords`, `Vision Analysis` (organizations, topics)
2. **Lucene index:** If the file is indexed, extract `keywords`, `summary`, `headings` fields
3. **Filename heuristic:** Fall back to tokenizing the filename (same as `DirectoryScorer.tokenize()`)

**Acceptance criteria:**
- Extracts topics and entities from companion files with AI enrichment
- Falls back to Lucene index data gracefully
- Falls back to filename tokenization when neither companion nor index is available
- Returns `EnrichmentSignature.empty()` when no signals are found
- Unit tests with mock companion files and mock index

**Effort:** 2 days
**Dependencies:** None (can parallel with P2-01/P2-02/P2-03)
**Risk:** Medium -- quality depends on enrichment data format, which varies. Mitigation: test with real companion files from the workspace.

---

#### P2-05: Build CentroidComputer

**Description:** Computes a `DirectoryCentroid` from the enrichment signatures of all files in a directory. This is the core centroid computation: aggregate topics (by frequency), entities (by frequency), document types, timeframe range, and confidence (based on enrichment coverage and cluster tightness).

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/CentroidComputer.java`

**Algorithm:**
1. For each file in the directory, get its `EnrichmentSignature`
2. Aggregate topics: count frequency, rank by frequency, take top N (configurable, default 10)
3. Aggregate entities: count frequency, rank, take top N (default 5)
4. Aggregate document types: collect unique
5. Compute timeframe: min/max of file timestamps, formatted as quarter range
6. Compute confidence: `enrichedFileCount / totalFileCount * clusterTightness`
   - `clusterTightness` = 1.0 - (unique topics / (totalTopics * 2)) -- tighter clusters have more repeated topics

**Acceptance criteria:**
- Given 8 files with overlapping topics, produces a centroid with ranked topics
- Confidence reflects enrichment coverage (50% enriched = lower confidence than 100%)
- Empty directory returns `DirectoryCentroid.empty()`
- Single file produces a centroid with that file's signature (confidence scaled down)
- Unit tests with synthetic enrichment signatures

**Effort:** 2 days
**Dependencies:** P2-04
**Risk:** Low -- algorithmic, well-defined inputs/outputs

**Design decision -- centroid update strategy:**
- Option A: Full recomputation on every sync (simpler, slower for large directories)
- Option B: Incremental update (track which files changed, update centroid delta)
- **Recommendation: Option A for Phase 2.** Directories rarely exceed 50-100 files. Full recomputation is cleaner and avoids drift. Phase 3 can add incremental if performance is an issue.

---

#### P2-06: Build WantsBootstrapper (cold-start)

**Description:** Generates initial `DirectoryWants` for directories that have no centroid (cold start). Uses the four-tier signal precedence from the vision:

| Tier | Signal | Confidence |
|------|--------|-----------|
| 1 | README or seed file | 0.5-0.7 |
| 2 | Directory name inference | 0.2-0.4 |
| 3 | Parent directory inheritance | 0.1-0.2 |
| 4 | Explicit override | 1.0 |

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/WantsBootstrapper.java`

**Implementation:**
- Tier 1: Check for README.md in the directory; if found, extract topics/entities from headings and first paragraph
- Tier 2: Use existing `DirectoryNameVocabulary` entries to generate topic keywords. Extend vocabulary to include a `topicHints` field in `IdentityTemplate`.
- Tier 3: If parent directory has a centroid, inherit its scope-level topics with lower confidence
- Tier 4: If an `overrides:` block exists with explicit wants, use that

**Acceptance criteria:**
- Empty directory named `opportunity-nova` gets wants: `["Nova Corp", "opportunity"]` from name inference
- Empty directory with a README describing "GreenField Energy partnership" gets wants from README content
- Empty directory under `clients/` inherits "client-material" topic from parent
- Wants include a `source` field describing provenance

**Effort:** 2 days
**Dependencies:** P2-01, P2-02 (needs DirectoryWants record and parser support)
**Risk:** Low-medium -- README parsing quality may vary. Mitigation: keep it simple (headings + first paragraph only, no AI).

---

#### P2-07: Integrate centroid computation into SyncCommand

**Description:** Extend `SyncCommand.syncWorkspace()` to compute centroids for directories that have enriched files, and bootstrap wants for directories that don't. Write the results to the `centroid:` and `wants:` blocks in `.synthesis.md`.

**Files:**
- Modified: `SyncCommand.java` -- after computing identity, also compute centroid and wants
- Modified: `DirectoryIdentityParser.write()` -- write the full `DirectoryProfile`

**Flow change:**
```
For each directory:
  1. [existing] Compute identity (vocab + signals + merge)
  2. [NEW] Extract enrichment signatures for all files
  3. [NEW] If enriched files exist: compute centroid via CentroidComputer
  4. [NEW] If centroid is absent/weak: bootstrap wants via WantsBootstrapper
  5. [existing] Write .synthesis.md (now includes centroid + wants)
```

**Acceptance criteria:**
- Sync produces `centroid:` blocks for directories with enriched files
- Sync produces `wants:` blocks for empty or new directories
- Directories with strong centroids (confidence > 0.8) do NOT get a `wants:` block (centroid IS the wants expression)
- Centroid data is also persisted to the SQLite `directory_centroids` table
- `--dry-run` shows centroid/wants that would be computed
- `--verbose` shows centroid topics and entities per directory

**Effort:** 3 days
**Dependencies:** P2-02, P2-05, P2-06
**Risk:** Medium -- this touches the core sync pipeline. Mitigation: behind a `--enrich-centroids` flag initially, default off. Graduate to default on after validation.

---

#### P2-08: Add `synthesis describe` command

**Description:** A new CLI command that explains what the system understands about a directory (or the full workspace). Uses centroid + wants + identity data to produce a human-readable description.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/DescribeCommand.java`
- Modified: `SynthesisApp.java` -- register the new subcommand

**CLI surface:**
```bash
synthesis describe                           # workspace-level summary
synthesis describe clients/opportunity-nova/ # directory-level detail

# Output example:
# clients/opportunity-greenfield/
#   Centroid: renewable energy, SDD methodology, workshop delivery
#   Key entities: GreenField Energy, Jane Smith
#   Timeframe: 2025-Q4 / 2026-Q1
#   Document types: proposal, contract, meeting-notes
#   Confidence: HIGH (0.87, 8 enriched files)
#   Wants: "Also looking for: invoice, mentoring contract"
#   Satisfaction: 87% (wants aligned with centroid)
#   Scope: CLIENT / eXOReaction / GreenField
```

**Acceptance criteria:**
- Workspace-level: shows top directories by centroid confidence, starving directories, drifting directories
- Directory-level: shows full centroid, wants, scope, confidence, contributing files
- Graceful when no centroid exists: shows identity data and wants bootstrap
- Integration test with test workspace

**Effort:** 2 days
**Dependencies:** P2-07 (needs centroids computed and stored)
**Risk:** Low -- read-only display command

---

#### P2-09: Add centroid-based similarity scoring to DirectoryScorer

**Description:** Extend `DirectoryScorer` to use centroid similarity when a file has enrichment data and a candidate directory has a centroid. This supplements (not replaces) the existing type/format/pattern/token scoring.

**Scoring addition:**
```
If file has EnrichmentSignature AND directory has DirectoryCentroid:
  topicOverlap = |file.topics ∩ dir.centroid.topics| / |file.topics|
  entityOverlap = |file.entities ∩ dir.centroid.entities| / max(|file.entities|, 1)
  centroidScore = (topicOverlap * 0.4) + (entityOverlap * 0.5) + (typeMatch * 0.1)

  // Blend with existing content score:
  contentScore = max(existingContentScore, centroidScore * dir.centroid.confidence)
```

**Files:**
- Modified: `DirectoryScorer.java` -- add `scoreCentroid()` method
- Modified: `DirectoryScorer.score()` -- invoke centroid scoring when data available

**Acceptance criteria:**
- When enrichment data is available, centroid scoring improves routing accuracy
- When enrichment data is unavailable (no companion, no index entry), falls back gracefully to existing scoring
- A file about "GreenField Energy, renewable energy" scores highest against a directory whose centroid includes those topics/entities
- Existing tests pass (no enrichment data = no centroid scoring = same as before)

**Effort:** 2 days
**Dependencies:** P2-04, P2-05 (needs enrichment signatures and centroids)
**Risk:** Medium -- blending two scoring mechanisms requires tuning. Mitigation: use `max()` blend so centroid can only *improve* scores, never reduce them.

---

#### P2-10: Add `overrides:` block support

**Description:** Introduce the `overrides:` block in `.synthesis.md` as the human-correction mechanism. Overrides are sync-immutable (never overwritten by sync). They express hard constraints that override both centroid and wants.

**Format:**
```yaml
overrides:
  label: "Opportunity: GreenField Energy"
  rejects_types: ["video"]
  transient: false
```

**Migration:** The existing `transient: true` written by hand, and `rejects_types` written by vocabulary, should be migrated to `overrides:` when `source: "manual"`. Non-manual sources keep the current format.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryOverrides.java`
- Modified: `DirectoryIdentityParser.java` -- parse/write `overrides:` block
- Modified: `SyncCommand.java` -- respect overrides (never overwrite)

**Acceptance criteria:**
- Sync never overwrites the `overrides:` block
- `overrides.rejects_types` is respected by routing (same as current `rejects_types` but explicit)
- `overrides.transient` overrides any computed transient value
- `overrides.label` is used by `synthesis describe` as the directory's human-facing name

**Effort:** 1.5 days
**Dependencies:** P2-02 (parser extension)
**Risk:** Low

---

#### P2-11: Confidence levels in human terms for `route explain`

**Description:** Extend the `route explain` output from Phase 1 to include human-readable confidence levels and centroid-based reasoning when available.

**Files:**
- Modified: `RouteExplainCommand.java`

**Output addition:**
```
synthesis route explain downloads/jane-smith-followup.pdf

  Top candidates:
  1. clients/opportunity-greenfield/ — CERTAIN (0.87)
     Entity match: "Jane Smith" (in 4/8 files)
     Topic match: "renewable energy" (primary topic)
     Centroid similarity: 0.91

  2. methodology/sdd/ — MODERATE (0.42)
     Topic match: "SDD methodology" (secondary)
     No entity match
```

**Effort:** 1 day
**Dependencies:** P2-09 (centroid scoring), P1-07 (route explain command)
**Risk:** Low

---

### Phase 2: Key Design Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| Store centroids in SQLite or only in `.synthesis.md`? | Both -- `.synthesis.md` is the source of truth; SQLite is for fast queries | Querying across hundreds of directories needs database; `.synthesis.md` is the filesystem-local truth |
| How to handle directories without enrichment? | Graceful degradation: use existing identity scoring, show "no enrichment" in `describe` | Must work for workspaces without API keys. Enrichment is optional enhancement. |
| Centroid update on every sync or only when enrichment changes? | Every sync but check timestamps -- skip if no files changed since last centroid update | Avoids unnecessary recomputation |
| Wants bootstrap: use AI for README parsing? | No -- use heading extraction + keyword matching only | Must work without API key; AI enrichment is a separate step |
| Expand DirectoryIdentity record? | No -- create DirectoryProfile wrapper | Keeps the identity record stable; avoids breaking 30+ construction sites |

### Phase 2: Cross-Cutting Concerns

**Backward compatibility:** `.synthesis.md` files written by v1.13 (Phase 1) are read correctly by v1.14 -- the parser ignores unknown blocks and returns `DirectoryCentroid.empty()`. Files written by v1.14 can be read by v1.13 -- the parser ignores the new blocks (already handles unknown keys gracefully).

**Incremental enrichment:** The centroid is computed from *whatever enrichment data is available*. A workspace with 0% enrichment gets no centroids (system falls back to Phase 1 scoring). A workspace with 20% enrichment gets partial centroids (lower confidence). 100% enrichment gets full centroids. The system degrades gracefully at every level.

**Performance:** Centroid computation for a directory with 50 files requires reading 50 enrichment signatures. If stored in SQLite (P2-03), this is a single indexed query. If reading companion files from disk, it's 50 file reads (fast for SSD). Full workspace sync with centroid computation adds ~2-5 seconds for 500 directories (estimated).

### Phase 2: Test Strategy

- **Unit tests:** `DirectoryCentroid`, `DirectoryWants`, `EnrichmentSignatureExtractor`, `CentroidComputer`, `WantsBootstrapper` -- all pure computation, easy to test
- **Parser tests:** Round-trip tests for the new `.synthesis.md` format (write -> read -> compare)
- **Integration tests:** End-to-end sync with a test workspace containing enriched and non-enriched files. Verify centroids are computed correctly.
- **Backward compatibility tests:** Parse v1.13 `.synthesis.md` files, verify no data loss. Parse v1.14 files with v1.13-compatible parser, verify graceful handling.

### Phase 2: Risk Assessment

- **Hardest part:** P2-04 (EnrichmentSignatureExtractor) -- extraction quality depends on companion file format, which is inconsistent (BASIC vs AI enrichment produces very different content).
- **Scope creep risk:** Adding AI-powered centroid computation. Mitigation: Phase 2 is *extraction from existing enrichment data*, not new AI calls.
- **Critical path:** P2-01 -> P2-02 -> P2-07. P2-04 -> P2-05 -> P2-07. These two chains merge at P2-07.

---

## Phase 3: Pull Model + Virtual Membership (v1.15.x)

### Summary

Phase 3 replaces the push routing model ("score the file, push it somewhere") with a pull model ("directories bid on files they want"). It introduces virtual membership (one file, many clusters) and want satisfaction metrics.

**What changes:** Routing becomes bidding. Virtual membership tracked in `.synthesis.md`. Want satisfaction computed per sync. Health signals for starvation and drift.

**Why it matters:** Pull-based routing scales better (adding a directory is self-registration, not central router modification), produces explainable decisions ("this directory wanted this file because..."), and enables want-based health signals.

**What it unlocks:** Phase 4's aspirational gap detection (wants that are never satisfied reveal structural gaps) and long-term learning (routing feedback improves want precision).

### Data Model Changes

#### Extended centroid for virtual membership

```yaml
centroid:
  # ... existing fields from Phase 2
  virtual_members: 2
  virtual_member_refs:
    - node: "clients/opportunity-greenfield/proposal-v2.pdf"
      relationship: "methodology application"
    - node: "media/marketing/videos/sdd-workshop-intro.mp4"
      relationship: "methodology demonstration"
```

#### New SQLite tables (Flyway V11)

```sql
-- V11__virtual_membership_and_routing_feedback.sql

-- Virtual membership links between files and directories
CREATE TABLE IF NOT EXISTS virtual_memberships (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,           -- the file (physical home elsewhere)
    directory_path TEXT NOT NULL,       -- the directory that has virtual membership
    relationship TEXT,                  -- e.g. "methodology application"
    bid_strength REAL NOT NULL,        -- how strongly the directory wanted this file
    created_at INTEGER NOT NULL,
    UNIQUE(workspace_path, file_path, directory_path)
);

CREATE INDEX IF NOT EXISTS idx_vm_workspace ON virtual_memberships(workspace_path);
CREATE INDEX IF NOT EXISTS idx_vm_file ON virtual_memberships(file_path);
CREATE INDEX IF NOT EXISTS idx_vm_directory ON virtual_memberships(directory_path);

-- Routing feedback: accepted/rejected routing decisions
CREATE TABLE IF NOT EXISTS routing_feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    file_path TEXT NOT NULL,
    proposed_destination TEXT NOT NULL,
    actual_destination TEXT,            -- null if rejected
    accepted INTEGER NOT NULL,          -- 1 or 0
    confidence_delta REAL,              -- how this feedback adjusts confidence
    timestamp INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rf_workspace ON routing_feedback(workspace_path);
CREATE INDEX IF NOT EXISTS idx_rf_file ON routing_feedback(file_path);
```

### Issues

#### P3-01: Implement directory bidding mechanism

**Description:** Create a `DirectoryBidder` class that, given an enriched file's signature, produces bids from all directories with centroids or wants. Each bid includes: directory path, bid strength, membership type (physical/virtual), and reasoning chain.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryBidder.java`
- New: `src/main/java/io/exoreaction/synthesis/org/Bid.java`

**Algorithm:**
```
For each directory with centroid or wants:
  bidStrength = semantic_similarity(file.signature, directory.wants_or_centroid)

  // Semantic similarity:
  topicSim = jaccard(file.topics, dir.topics) * 0.40
  entitySim = jaccard(file.entities, dir.entities) * 0.45
  typeSim = (file.docType in dir.docTypes) ? 0.10 : 0.0
  timeframeSim = timeframeOverlap(file, dir) * 0.05

  bidStrength = topicSim + entitySim + typeSim + timeframeSim
  bidStrength *= dir.centroid.confidence  // higher-confidence dirs bid more strongly

Rank bids:
  Winner (highest bid) -> physical membership
  Runners-up (bid > 0.3) -> virtual membership candidates
  No match (all bids < 0.1) -> orphan
```

**Acceptance criteria:**
- Bidding produces ranked results with explainable reasoning
- Winner gets PHYSICAL membership designation
- Strong runners-up (>0.3) get VIRTUAL membership designation
- Files with no strong match are flagged as orphans
- Unit tests with synthetic centroids and signatures

**Effort:** 3 days
**Dependencies:** Phase 2 complete (centroids and wants exist)
**Risk:** Medium -- semantic similarity quality depends on enrichment quality

---

#### P3-02: Integrate bidding into unified routing pipeline

**Description:** Extend `DirectoryIdentityRouter` to use `DirectoryBidder` when enrichment data is available. The bidding results supplement (and for enriched files, replace) the traditional identity scoring.

**Files:**
- Modified: `DirectoryIdentityRouter.java`
- Modified: `RoutingDecision.java` -- add virtual membership proposals

**Routing cascade (updated):**
```
1. RoutingHints (learned patterns) -- if match, return immediately
2. ConfigRules (glob + keyword) -- if match, return immediately
3. DirectoryBidder (enrichment-based bidding) -- if enriched file
4. DirectoryScorer (identity-based scoring) -- fallback for non-enriched files
```

**Acceptance criteria:**
- Enriched files are routed via bidding (better accuracy)
- Non-enriched files fall back to identity scoring (Phase 1 behavior preserved)
- `route explain` shows bidding results for enriched files
- Performance: bidding across 200 directories completes in <100ms

**Effort:** 2 days
**Dependencies:** P3-01
**Risk:** Medium -- ensuring fallback works seamlessly

---

#### P3-03: Implement virtual membership tracking

**Description:** When routing produces virtual membership candidates, record them in the SQLite `virtual_memberships` table and in the `.synthesis.md` `centroid.virtual_member_refs` block.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/VirtualMembershipManager.java`
- Modified: `DirectoryIdentityParser.java` -- write `virtual_member_refs` block
- Modified: `SyncCommand.java` -- update virtual membership counts in centroid

**Acceptance criteria:**
- Virtual memberships are recorded in SQLite
- Virtual member refs appear in `.synthesis.md` centroid block
- `synthesis describe` shows virtual members
- Virtual members contribute to the directory's centroid (with lower weight than physical members)

**Effort:** 2 days
**Dependencies:** P3-02, Flyway V11
**Risk:** Low

---

#### P3-04: Implement want satisfaction metric

**Description:** Compute `wants.satisfaction` for each directory on sync. The metric measures how well the current centroid matches the stated wants.

**Formula:**
```
satisfaction = 0.0

If wants.topics is non-empty:
  topicCoverage = |centroid.topics ∩ wants.topics| / |wants.topics|
  satisfaction += topicCoverage * 0.5

If wants.entities is non-empty:
  entityCoverage = |centroid.entities ∩ wants.entities| / |wants.entities|
  satisfaction += entityCoverage * 0.3

If wants.alsoLookingFor is non-empty:  (Phase 4, but compute even if empty)
  gapsFilled = |wants.alsoLookingFor ∩ centroid.documentTypes| / |wants.alsoLookingFor|
  satisfaction += gapsFilled * 0.2

// Clamp to 0.0-1.0
satisfaction = min(satisfaction, 1.0)
```

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/WantSatisfactionComputer.java`
- Modified: `SyncCommand.java` -- compute satisfaction during sync

**Acceptance criteria:**
- Directory with wants fully covered by centroid -> satisfaction ~1.0
- Directory with wants completely uncovered -> satisfaction 0.0
- Formula produces sensible intermediate values
- Unit tests with various coverage scenarios

**Effort:** 1 day
**Dependencies:** P2-05, P2-06 (needs centroids and wants)
**Risk:** Low

---

#### P3-05: Flyway V11 -- virtual membership and routing feedback tables

**Description:** Create the Flyway migration for virtual memberships and routing feedback.

**Files:**
- New: `src/main/resources/db/migration/V11__virtual_membership_and_routing_feedback.sql`

**Effort:** 0.5 day
**Dependencies:** None
**Risk:** Low

---

#### P3-06: Add want starvation health signal (W020)

**Description:** Add a new health check that detects directories whose wants have never been satisfied: they have clear wants but zero or low satisfaction.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/W020Check.java`
- Modified: `HealthCommand.java` -- integrate W020

**Output:**
```
[W020] Want starvation: clients/opportunity-nova/
  Wants: Nova Corp, CTO partnership, cloud infrastructure (since: 2026-02-15)
  Satisfaction: 0.0 (NONE) -- file count: 0
  Days since creation: 6
  Suggest: Is this opportunity still active?
```

**Acceptance criteria:**
- Detects directories with wants.satisfaction < 0.1 and age > 3 days
- Reports age, wants topics, and suggestion
- Does not fire for directories without wants (no false positives)

**Effort:** 1 day
**Dependencies:** P3-04
**Risk:** Low

---

#### P3-07: Add want drift health signal (W021)

**Description:** Detect directories where the centroid has diverged significantly from the stated wants. This means the directory is attracting content that doesn't match its original purpose.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/W021Check.java`
- Modified: `HealthCommand.java` -- integrate W021

**Acceptance criteria:**
- Detects directories where wants.satisfaction < 0.4 AND centroid.confidence > 0.5 (significant centroid exists but doesn't match wants)
- Reports the divergence: centroid topics vs wants topics
- Suggests: route mismatched files elsewhere, or update wants to match reality

**Effort:** 1 day
**Dependencies:** P3-04
**Risk:** Low

---

#### P3-08: Implement `synthesis feedback` command

**Description:** A command that records whether a routing decision was correct. This feeds into the routing feedback table and adjusts future bidding weights.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/FeedbackCommand.java`
- Modified: `SynthesisApp.java`

**CLI surface:**
```bash
synthesis feedback accept <file>   # confirm the file is in the right place
synthesis feedback reject <file>   # this file should be elsewhere
```

**Effort:** 1.5 days
**Dependencies:** P3-05 (routing feedback table)
**Risk:** Low

---

#### P3-09: Add `health:` block to `.synthesis.md`

**Description:** Add the fourth layer to the `.synthesis.md` format: computed health signals per directory.

**Format:**
```yaml
health:
  cohesion: 0.91
  drift: false
  satisfaction: 0.87
  status: "healthy"           # healthy | bootstrapping | starving | drifting
  outliers: []
```

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryHealth.java`
- Modified: `DirectoryIdentityParser.java` -- parse/write health block
- Modified: `SyncCommand.java` -- compute health during sync

**Effort:** 1.5 days
**Dependencies:** P3-04, P3-06, P3-07
**Risk:** Low

---

### Phase 3: Key Design Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| Virtual membership: symlinks or database-only? | Database-only (SQLite + .synthesis.md refs) | Symlinks are fragile across platforms. The database is the source of truth. |
| How many virtual memberships per file? | Cap at 3 | Prevents every file from appearing in every directory. Top 3 runners-up is sufficient. |
| Routing feedback: interactive or batch? | Both: `synthesis feedback` for interactive, automatic tracking for `staging route` decisions | Interactive for education; automatic for learning |
| Should bidding run on every `maintain`? | Only when enrichment data has changed since last bid | Avoids expensive recomputation on every cron run |

### Phase 3: Performance Considerations

- **Bidding across 200 directories:** Each bid is a set intersection (topics, entities). With 10 topics and 5 entities per centroid, this is ~3000 set operations. Estimated: <50ms.
- **Virtual membership updates:** Writing 3 virtual memberships per routed file is 3 SQLite inserts. Batch-writable.
- **Satisfaction computation:** O(n) per directory where n = |wants.topics| + |wants.entities|. Negligible.

### Phase 3: Test Strategy

- **Unit tests:** `DirectoryBidder`, `VirtualMembershipManager`, `WantSatisfactionComputer` -- all pure computation
- **Integration tests:** End-to-end routing of an enriched file through bidding, verify physical + virtual placement
- **Health check tests:** `W020Check`, `W021Check` with synthetic directory profiles

---

## Phase 4: Full Knowledge Graph (v2.0)

### Summary

Phase 4 completes the vision: aspirational gap detection, want conflict resolution, knowledge graph visualization, workspace discovery, and long-term learning. This is the north star -- the system that reads your information architecture, explains what each part wants, detects what's missing, and gets smarter over time.

**What changes:** Archetype-based gap detection, entity-centric graph views, structural discovery, long-term learning from routing feedback.

**Why it matters:** This is where Synthesis moves from "smart filing system" to "knowledge graph that understands your work." The gap detection and structural analysis provide value that no file organizer can match.

**What it unlocks:** The full vision: a system that reads existing architecture, explains what it sees, detects what's missing, routes files to where they belong, and learns from every decision.

### Issues

#### P4-01: Define directory archetypes

**Description:** Create a registry of known directory archetypes (patterns of what complete directories look like). For example: a "client opportunity" archetype typically contains: proposal, contract, meeting notes, invoice. A "project" archetype typically contains: design doc, implementation, tests, README.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/DirectoryArchetype.java`
- New: `src/main/java/io/exoreaction/synthesis/org/ArchetypeRegistry.java`

**Data model:**
```java
public record DirectoryArchetype(
    String name,                    // "client-opportunity", "project", "methodology"
    List<String> expectedTopics,    // topics that should be present
    List<String> expectedDocTypes,  // document types that should be present
    double matchThreshold           // how closely a centroid must match to trigger
) {}
```

**Acceptance criteria:**
- At least 6 archetypes: client-opportunity, project, methodology, marketing-campaign, product, archive
- Archetypes are extensible (loaded from config or built-in defaults)
- Unit tests verify archetype matching against sample centroids

**Effort:** 1.5 days
**Dependencies:** Phase 2 (centroids exist)
**Risk:** Low

---

#### P4-02: Implement aspirational gap detection

**Description:** For each directory, compare its centroid against matching archetypes. If the archetype expects document types that the centroid lacks, report them as aspirational gaps. These gaps feed into the `wants.also_looking_for` field.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/GapAnalyzer.java`
- Modified: `SyncCommand.java` -- compute gaps during sync, populate `also_looking_for`

**Output (in `synthesis describe`):**
```
clients/opportunity-greenfield/
  Archetype match: "client-opportunity" (0.87)
  Gaps: invoice (missing from archetype), mentoring contract (missing)
  Wants.also_looking_for: ["invoice", "mentoring contract"]
```

**Effort:** 2 days
**Dependencies:** P4-01, Phase 2 (centroids)
**Risk:** Medium -- archetype matching quality requires real-world tuning

---

#### P4-03: Add want conflict health signal (I021)

**Description:** Detect when multiple directories are bidding for the same type of file (want conflict). This indicates overlapping purposes that should be resolved through virtual membership or directory restructuring.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/I021Check.java`

**Effort:** 1.5 days
**Dependencies:** Phase 3 (bidding exists)
**Risk:** Low

---

#### P4-04: Add want fulfillment health signal (I020)

**Description:** Positive signal: the directory is getting what it wants. Reports directories with high satisfaction and recent inbound files.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/I020Check.java`

**Effort:** 1 day
**Dependencies:** Phase 3 (satisfaction metric)
**Risk:** Low

---

#### P4-05: Implement `synthesis graph` command

**Description:** Visualize the knowledge graph in ASCII, Mermaid, or JSON format. Shows directories as nodes, virtual memberships as edges, entity relationships as cross-links.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/GraphCommand.java`

**CLI surface:**
```bash
synthesis graph                               # ASCII overview
synthesis graph --format mermaid              # Mermaid diagram
synthesis graph --entity "GreenField Energy"  # entity-centric view
synthesis graph --format json                 # machine-readable
```

**Effort:** 3 days
**Dependencies:** Phase 3 (virtual memberships, centroids)
**Risk:** Medium -- ASCII rendering of complex graphs is non-trivial. Start with Mermaid output.

---

#### P4-06: Implement `synthesis discover` command

**Description:** Structural analysis command that surfaces emerging patterns, orphan files, fragmentation, and gaps across the entire workspace.

**CLI surface:**
```bash
synthesis discover                  # full structural analysis
synthesis discover --orphans        # files with no semantic home
synthesis discover --fragmentation  # concepts split across dirs
synthesis discover --gaps           # aspirational gaps across workspace
```

**Effort:** 3 days
**Dependencies:** Phase 3 (centroids, virtual memberships), P4-02 (gaps)
**Risk:** Medium

---

#### P4-07: Long-term learning from routing feedback

**Description:** Use accumulated routing feedback to adjust bidding weights. Directories that consistently have routing decisions confirmed get higher bid confidence. Directories with rejected decisions get lower confidence.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/org/RoutingLearner.java`
- Modified: `DirectoryBidder.java` -- weight bids by historical accuracy

**Effort:** 2 days
**Dependencies:** P3-08 (feedback command), P3-05 (feedback table)
**Risk:** Medium -- learning rate tuning

---

#### P4-08: Structural evolution reports

**Description:** Periodic reports showing how the workspace has evolved: which directories are growing, which are starving, which wants were satisfied, which gaps persist.

**Files:**
- New: `src/main/java/io/exoreaction/synthesis/cli/EvolutionReportCommand.java`

**Effort:** 2 days
**Dependencies:** Phase 3 complete
**Risk:** Low

---

#### P4-09: Full `synthesis describe` -- natural language workspace explanation

**Description:** Extend `synthesis describe` to produce natural language explanations of the full workspace information architecture. Uses centroids, wants, health, and archetypes to explain the workspace in human terms.

**Effort:** 2 days
**Dependencies:** Phase 4 prior issues
**Risk:** Low (AI-enhanced, graceful degradation to non-AI summary)

---

### Phase 4: Key Design Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| Archetypes: built-in only or user-configurable? | Both: built-in defaults + override in `config.yaml` | Sensible defaults, extensible for specific workspaces |
| Graph format priority? | Mermaid first, JSON second, ASCII last | Mermaid is the most useful for documentation and sharing |
| Learning rate for routing feedback? | Conservative: 0.02 per positive, -0.01 per negative | Avoid oscillation; positive reinforcement stronger than negative |
| Natural language describe: require AI? | No -- structured template without AI, enhanced with AI when available | Must work offline |

---

## Cross-Cutting Concerns

### 1. Backward Compatibility

**Migration path for `.synthesis.md` files:**
- v1.13 writes: `accepts`, `scope`, `confidence`, `transient`, `aliases`, `rejects_types`, `moved_files`
- v1.14 adds: `centroid`, `wants` (new blocks, additive -- v1.13 parser ignores them)
- v1.15 adds: `health`, `centroid.virtual_member_refs` (additive)
- v2.0 adds: `overrides` (final layer)

**Parser behavior:**
- Unknown YAML keys are silently ignored (already the case in the line-based parser)
- Missing blocks return empty records (already the case for `movedFiles`)
- A v1.15 parser reading a v1.13 file gets `DirectoryCentroid.empty()` and `DirectoryWants.empty()` -- correct behavior

**No destructive migration required.** All format changes are additive.

### 2. Incremental Enrichment / Graceful Degradation

| Enrichment level | Centroid quality | Routing quality | Health signals |
|-----------------|------------------|-----------------|----------------|
| 0% (no enrichment) | No centroid | Phase 1 identity scoring only | E010 only |
| 20% (partial) | Low-confidence centroid | Blended: identity + partial centroid | Basic starvation/drift |
| 100% (full AI) | High-confidence centroid | Full bidding model | Complete health suite |

**Key principle:** Every feature degrades gracefully. Nothing breaks when enrichment is unavailable -- the system simply falls back to the previous phase's behavior.

### 3. Performance Budget

| Operation | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|-----------|---------|---------|---------|---------|
| Sync (500 dirs) | ~5s | ~7s (+centroid) | ~8s (+satisfaction) | ~10s (+gaps) |
| Route one file | <10ms | <20ms (+centroid score) | <50ms (+bidding) | <50ms |
| Health check | ~3s | ~4s | ~5s (+W020/W021) | ~7s (+I020/I021) |
| Graph generation | N/A | N/A | N/A | ~3s (500 dirs) |

**Lazy computation strategy:**
- Centroids: computed on sync, cached in SQLite. NOT recomputed on route.
- Bidding: computed per routed file. Candidate list is cached per session.
- Health signals: computed on `synthesis health` only, not on every maintain.
- Gap detection: computed on `synthesis discover` only.

### 4. The Wants Bootstrap Evolution

**Phase 2:** `DirectoryNameVocabulary` is extended with `topicHints` per entry. The vocabulary HashMap becomes the Tier 2 signal for wants bootstrapping.

```java
// Example: "marketing" entry gets topic hints
new IdentityTemplate(
    List.of("marketing"), List.of("md", "pdf", "png", "mp4"),
    DEFAULT_CONFIDENCE, true, List.of(), List.of(),
    List.of("marketing campaigns", "brand materials", "social media")  // NEW: topicHints
);
```

**Phase 3:** Bootstrapped wants are validated against incoming content. If 5+ files arrive that match the bootstrapped wants, the wants graduate to centroid-based (the wants block is removed; centroid speaks for itself).

**Phase 4:** Archetype matching adds aspirational wants (`also_looking_for`) that the vocabulary cannot express.

### 5. The Wants Satisfaction Metric (Complete Formula)

```
satisfaction(directory) =
  let w = directory.wants
  let c = directory.centroid

  if w is empty: return 1.0  // no explicit wants = satisfied by definition
  if c is empty: return 0.0  // has wants but nothing to satisfy them

  topicCoverage  = jaccard(c.topics, w.topics)           * 0.45
  entityCoverage = jaccard(c.entities, w.entities)        * 0.35
  gapsFilled     = |w.alsoLookingFor ∩ c.documentTypes|
                   / max(|w.alsoLookingFor|, 1)          * 0.20

  satisfaction = topicCoverage + entityCoverage + gapsFilled

  // Time decay: satisfaction drops if no new files arrived recently
  daysSinceLastFile = (now - latestFileTimestamp) / 86400
  if daysSinceLastFile > 30:
    satisfaction *= 0.9  // mild staleness penalty
  if daysSinceLastFile > 90:
    satisfaction *= 0.7  // significant staleness penalty

  return clamp(satisfaction, 0.0, 1.0)

where jaccard(A, B) = |A ∩ B| / |A ∪ B|
```

### 6. CLI Surface by Phase

| Phase | New commands | Modified commands |
|-------|-------------|-------------------|
| **Phase 1** | `synthesis route explain <file>` | `synthesis sync` (depth guard), `synthesis health` (unified E010) |
| **Phase 2** | `synthesis describe [dir]` | `synthesis sync` (centroids + wants), `synthesis route explain` (centroid reasoning) |
| **Phase 3** | `synthesis feedback accept/reject <file>` | `synthesis route` (bidding), `synthesis health` (W020, W021), `synthesis describe` (virtual members) |
| **Phase 4** | `synthesis graph`, `synthesis discover` | `synthesis health` (I020, I021), `synthesis describe` (full NL) |

---

## Appendix A: Complete List of New Classes

| Phase | Class | Package | Purpose |
|-------|-------|---------|---------|
| 1 | `MediaTypes` | `util` | Shared media extension constants |
| 1 | `RoutingContext` | `org` | Routing caller preferences |
| 1 | `RoutingDecision` | `org` | Routing result with reasoning |
| 1 | `RoutingConfidence` | `org` | Human-readable confidence enum |
| 1 | `RouteExplainCommand` | `cli` | `synthesis route explain` CLI command |
| 2 | `DirectoryCentroid` | `org` | Semantic centroid record |
| 2 | `DirectoryWants` | `org` | Wants expression record |
| 2 | `DirectoryProfile` | `org` | Wrapper: identity + centroid + wants |
| 2 | `DirectoryOverrides` | `org` | Human overrides record |
| 2 | `EnrichmentSignatureExtractor` | `org` | Extract semantic signature from enriched file |
| 2 | `EnrichmentSignature` | `org` | Per-file semantic signature record |
| 2 | `CentroidComputer` | `org` | Compute centroid from enrichment signatures |
| 2 | `WantsBootstrapper` | `org` | Cold-start wants generation |
| 2 | `DescribeCommand` | `cli` | `synthesis describe` CLI command |
| 3 | `DirectoryBidder` | `org` | Pull-based routing: directories bid on files |
| 3 | `Bid` | `org` | Single bid from a directory for a file |
| 3 | `VirtualMembershipManager` | `org` | Track virtual memberships |
| 3 | `WantSatisfactionComputer` | `org` | Compute wants.satisfaction metric |
| 3 | `DirectoryHealth` | `org` | Per-directory health record |
| 3 | `W020Check` | `cli` | Want starvation health check |
| 3 | `W021Check` | `cli` | Want drift health check |
| 3 | `FeedbackCommand` | `cli` | `synthesis feedback` CLI command |
| 4 | `DirectoryArchetype` | `org` | Archetype definition |
| 4 | `ArchetypeRegistry` | `org` | Built-in + custom archetypes |
| 4 | `GapAnalyzer` | `org` | Aspirational gap detection |
| 4 | `I020Check` | `cli` | Want fulfillment health signal |
| 4 | `I021Check` | `cli` | Want conflict health signal |
| 4 | `GraphCommand` | `cli` | `synthesis graph` CLI command |
| 4 | `DiscoverCommand` | `cli` | `synthesis discover` CLI command |
| 4 | `RoutingLearner` | `org` | Long-term learning from feedback |
| 4 | `EvolutionReportCommand` | `cli` | Workspace evolution report |

**Total: 31 new classes across 4 phases.**

## Appendix B: Classes to Delete

| Phase | Class | Reason |
|-------|-------|--------|
| 1 | `SubjectBasedRouter` | Absorbed into `DirectoryScorer` / `DirectoryIdentityRouter` |

## Appendix C: Classes with Significant Modification

| Phase | Class | Nature of change |
|-------|-------|-----------------|
| 1 | `DirectoryIdentityParser` | Confidence-weighted merge, new block parsing |
| 1 | `SyncCommand` | Depth guard, centroid integration |
| 1 | `DirectoryIdentityRouter` | RoutingContext/RoutingDecision, unified routing |
| 1 | `DirectoryScorer` | Score normalization, centroid scoring |
| 1 | `E010Check` | Use unified router instead of SubjectBasedRouter |
| 1 | `MaintainCommand` / `MaintainOrchestrator` | Use unified router for rebalance |
| 2 | `DirectoryIdentityParser` | Parse/write centroid, wants, overrides blocks |
| 2 | `SyncCommand` | Compute centroids, bootstrap wants |
| 3 | `DirectoryIdentityRouter` | Bidding integration |
| 3 | `SyncCommand` | Satisfaction computation, health blocks |
| 3 | `HealthCommand` | W020, W021 integration |
| 4 | `HealthCommand` | I020, I021 integration |
| 4 | `SynthesisApp` | Register graph, discover, evolution commands |

## Appendix D: Flyway Migrations

| Migration | Phase | Tables created |
|-----------|-------|---------------|
| V10__directory_centroids.sql | 2 | `directory_centroids`, `file_enrichment_signatures` |
| V11__virtual_membership_and_routing_feedback.sql | 3 | `virtual_memberships`, `routing_feedback` |

## Appendix E: Lucene Index Schema (No Changes Required)

The existing Lucene schema (`DocumentFields.java`) already includes the fields needed for enrichment signature extraction:
- `KEYWORDS` -- maps to topics
- `SUMMARY` -- maps to document understanding
- `HEADINGS` -- maps to entities (via extraction)
- `ORGANIZATION`, `CLIENT` -- maps to entities directly
- `EMBEDDING` -- reserved for future semantic search (not used yet)

No Lucene schema changes are needed. All centroid computation reads from existing indexed fields or companion files.

---

*This plan should be reviewed after Phase 1 is complete. Real-world performance data and routing accuracy metrics from Phase 1 will inform threshold tuning for Phase 2.*

*Next steps: Create GitHub issues from Phase 1 issues (P1-01 through P1-08). Implement in dependency order.*
