# Sync & Routing Subsystem: Architecture Report

**Date:** 2026-02-21
**Author:** Claude Opus 4.6 (deep analysis session)
**Scope:** Directory identity sync pipeline, file routing pipeline, and their interaction
**Version analyzed:** v1.12.2-SNAPSHOT

---

## Table of Contents

1. [Current Architecture](#1-current-architecture)
   - [The Sync Pipeline](#11-the-sync-pipeline)
   - [The Routing Pipeline](#12-the-routing-pipeline)
   - [How Sync and Routing Interact](#13-how-sync-and-routing-interact)
   - [The `.synthesis.md` File Format](#14-the-synthesismd-file-format)
2. [Design Problems](#2-design-problems)
   - [P1: Vocabulary/Signals Merge Tension](#p1-vocabularysignals-merge-tension)
   - [P2: Transient Concept Scope Creep](#p2-transient-concept-scope-creep)
   - [P3: Routing Score Calibration Gap](#p3-routing-score-calibration-gap)
   - [P4: Routing Pipeline Fragmentation](#p4-routing-pipeline-fragmentation)
   - [P5: Identity Merge Semantic Confusion](#p5-identity-merge-semantic-confusion)
   - [P6: Missing Graduation Mechanism](#p6-missing-graduation-mechanism)
   - [P7: Depth Blindness in Vocabulary](#p7-depth-blindness-in-vocabulary)
   - [P8: MEDIA_EXTENSIONS Duplication](#p8-media_extensions-duplication)
3. [Enhanced Design Proposals](#3-enhanced-design-proposals)
   - [D1: Depth-Aware Transient Detection](#d1-depth-aware-transient-detection)
   - [D2: Unified Routing Pipeline](#d2-unified-routing-pipeline)
   - [D3: Score Calibration Overhaul](#d3-score-calibration-overhaul)
   - [D4: Human-Transparent Routing Decisions](#d4-human-transparent-routing-decisions)
   - [D5: Graduation Mechanism](#d5-graduation-mechanism)
   - [D6: Priority-Based Merge Semantics](#d6-priority-based-merge-semantics)
4. [Recommended Next Steps](#4-recommended-next-steps)

---

## 1. Current Architecture

### 1.1 The Sync Pipeline

The sync pipeline discovers directories, infers their identity (what files they accept, what scope they belong to), and writes `.synthesis.md` metadata files. This is the **supply side** of the routing system -- it creates the directory identity data that routing consumes.

#### Data Flow

```
                              ┌──────────────────┐
                              │   SyncCommand     │
                              │   .call()         │
                              └────────┬─────────┘
                                       │
                            Files.walk(scanRoot)
                                       │
                              filter: !hidden, !.synthesis,
                              !excludePattern, !codePackage,
                              !deepArchive
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
              Precedence 1       Precedence 2       Precedence 3
              ┌─────────┐      ┌──────────────┐    ┌───────────┐
              │ MANUAL   │      │ CONFIG ENTRY │    │ INFERENCE │
              │ source=  │      │ SubWorkspace │    │           │
              │ "manual" │      │ entries from │    │           │
              │ → SKIP   │      │ config.yaml  │    │           │
              └─────────┘      └──────┬───────┘    └─────┬─────┘
                                      │                  │
                               confidence: 0.95    ┌─────┴──────────────┐
                               source: "config     │                    │
                                 entry"            ▼                    ▼
                                          ┌────────────────┐  ┌─────────────────┐
                                          │ Vocabulary      │  │ SignalExtractor  │
                                          │ inferFromName() │  │ extract()        │
                                          │                 │  │                  │
                                          │ Name-based      │  │ Content-based    │
                                          │ lookup in       │  │ scan of files:   │
                                          │ static HashMap  │  │ - extensions     │
                                          │                 │  │ - filename tokens│
                                          │ Outputs:        │  │ - type inference │
                                          │ - types         │  │ - confidence     │
                                          │ - formats       │  │   (file count    │
                                          │ - confidence    │  │    based)        │
                                          │ - transient_    │  │                  │
                                          │ - rejectsTypes  │  │ Always outputs:  │
                                          └───────┬────────┘  │ transient_=false │
                                                  │            └────────┬────────┘
                                                  │                     │
                                                  ▼                     ▼
                                          ┌──────────────────────────────┐
                                          │ DirectoryIdentityParser      │
                                          │ .merge(vocabResult,          │
                                          │        signalsIdentity)      │
                                          │                              │
                                          │ Union types/formats/patterns │
                                          │ Max confidence               │
                                          │ OR for transient_    ◄─── PROBLEM
                                          └──────────────┬───────────────┘
                                                         │
                                                    "discovered"
                                                         │
                                              ┌──────────┴──────────┐
                                              │ existing .synthesis │
                                              │ .md on disk?        │
                                              ├──────┬──────────────┤
                                              │ YES  │     NO       │
                                              ▼      │              ▼
                                     merge(existing,  │     result = discovered
                                           discovered)│
                                              │       │
                                              ▼       │
                                     parser.write()   │
                                              │       │
                                              ▼       ▼
                                    .synthesis.md written to disk
```

#### Key Components

**`SyncCommand`** (`cli/SyncCommand.java`, 535 lines):
- Entry point: `syncWorkspace(Path workspaceRoot)`
- Walks all directories under the scan root
- Applies 3-tier precedence: manual > config > inference
- For inference: runs both `DirectoryNameVocabulary` and `DirectorySignalExtractor`, then merges
- Writes `.synthesis.md` files via `DirectoryIdentityParser.write()`
- Reports: created / updated / unchanged counts

**`DirectoryNameVocabulary`** (`org/DirectoryNameVocabulary.java`, 245 lines):
- Static `HashMap<String, IdentityTemplate>` with 30+ entries
- Normalizes directory names: lowercase, strip hyphens/underscores/spaces
- Returns `Optional<DirectoryIdentity>` -- empty for unrecognized names
- **Critical:** Three entries have `transient_=true`: "marketing", "staging", "incoming"
- Does NOT receive path context -- only the directory name

**`DirectorySignalExtractor`** (`org/DirectorySignalExtractor.java`, 250 lines):
- Scans immediate children (non-recursive) of a directory
- Counts file extensions, tokenizes filenames
- Infers types from tokens (keyword matching) and format ratios (>60% media = "media" type)
- Confidence is purely file-count-based: 0 files=0.0, 1-3=0.5, 4-10=0.7, 11-19=0.85, 20+=0.94
- **Always outputs `transient_=false`** -- signals never assert transient

**`DirectoryIdentityParser`** (`org/DirectoryIdentityParser.java`, 531 lines):
- Line-based YAML front matter parser (no SnakeYAML dependency for parse)
- Writes `.synthesis.md` files with structured YAML front matter
- `merge()` method: union lists, max confidence, OR for transient_, keep existing for source/description

**`DirectoryIdentity`** (`org/DirectoryIdentity.java`, 101 lines):
- Java record with 14 fields
- Backward-compatible 10-field constructor (new fields default to empty/false)
- `empty()` factory for null-object pattern

### 1.2 The Routing Pipeline

The routing pipeline determines where files should go. It is consumed by multiple commands that each trigger routing in different contexts.

#### The Five Routing Mechanisms

```
                         FILE ROUTING IN SYNTHESIS
                         ═════════════════════════

    ┌─────────────────────────────────────────────────────────────┐
    │                                                             │
    │  1. CONFIG ROUTING RULES          (StagingCommand / Sweep)  │
    │     RoutingRule: glob patterns + keywords → destination     │
    │     Threshold: exact match (glob) or keyword match          │
    │     Used by: staging route (Phase 2), sweep (Phase 4)       │
    │                                                             │
    ├─────────────────────────────────────────────────────────────┤
    │                                                             │
    │  2. DIRECTORY IDENTITY ROUTER     (Sweep / Archive rebal.)  │
    │     DirectoryIdentityRouter → DirectoryScorer               │
    │     Threshold: 0.5 (sweep), 0.7 (archive rebalance)        │
    │     Scoring: type + format + pattern + token + scope bonus  │
    │     Max possible: 1.0 content + 0.64 scope = 1.64          │
    │     Used by: sweep fallback (Phase 4), archive rebal. (5)  │
    │                                                             │
    ├─────────────────────────────────────────────────────────────┤
    │                                                             │
    │  3. SUBJECT-BASED ROUTER          (Transient rebalance)     │
    │     SubjectBasedRouter: filename tokens vs dir+alias tokens │
    │     Threshold: 0.7 (transient rebalance), 0.4 (E010 check) │
    │     Scoring: overlap_ratio * identity_confidence            │
    │     Max possible: 1.0 * 0.94 = 0.94                        │
    │     Used by: transient rebalance (Phase 5), E010 health     │
    │                                                             │
    ├─────────────────────────────────────────────────────────────┤
    │                                                             │
    │  4. ROUTING HINTS                 (DirectoryIdentityRouter) │
    │     RoutingHints: .synthesis/routing-hints.json             │
    │     Score: synthetic 0.9 (bypasses regular scoring)         │
    │     Learned from past routing decisions                     │
    │     Used by: DirectoryIdentityRouter before scorer          │
    │                                                             │
    ├─────────────────────────────────────────────────────────────┤
    │                                                             │
    │  5. E010 HEALTH CHECK             (Read-only diagnosis)     │
    │     E010Check: finds files in transient/reject dirs         │
    │     Threshold: 0.4 WARNING, 0.8 (designed, never fires)    │
    │     Uses SubjectBasedRouter internally                      │
    │     Does NOT move files -- only reports findings            │
    │                                                             │
    └─────────────────────────────────────────────────────────────┘
```

#### Routing Cascade in `synthesis maintain`

```
  Phase 1: Ingest
      └─ StagingManager: register new files from staging areas

  Phase 2: Route (staging route)
      └─ Config routing rules (glob patterns + keywords)
         └─ StagingManager.routeTo() → copy to dest, rename source *_processed*

  Phase 3: Sync
      └─ SyncCommand: refresh .synthesis.md files (supply side)

  Phase 4: Sweep
      └─ Config routing rules (glob + keywords)
         └─ DirectoryIdentityRouter (score >= 0.5, non-ambiguous)
            └─ Archive fallback (archive/swept-{date}/)

  Phase 5: Rebalance
      ├─ Archive rebalance:
      │     DirectoryIdentityRouter (score >= 0.7, non-ambiguous)
      └─ Transient rebalance:
            SubjectBasedRouter (score >= 0.7, media files only)

  [Health check -- not in maintain, separate command]
  E010Check:
      SubjectBasedRouter (score >= 0.4 → WARNING, unmatched → INFO)
```

#### Key Routing Components

**`DirectoryIdentityRouter`** (`org/DirectoryIdentityRouter.java`, 191 lines):
- Orchestrates `DirectoryScorer` over all workspace directories with `.synthesis.md`
- Checks `RoutingHints` first (priority: hints > scoring)
- Candidate discovery: walks full workspace, parses all `.synthesis.md` files
- Lazily caches candidate list per instance
- Returns `RouteResult(ScoredCandidate, ambiguous)`

**`DirectoryScorer`** (`org/DirectoryScorer.java`, 445 lines):
- Multi-signal scoring: type match (0.3/0.15), format match (0.2), pattern match (0.3), filename token match (0.25 max)
- Content score capped at 1.0
- Scope bonus: org match (+0.24), entity match (+0.40), max 0.64
- Hard-reject guard: `rejectsTypes` blocks matching files (score=0.0, blocked=true)
- Ambiguity detection: top two within 0.15 of each other and both >0.1
- Distinguishes specific vs generic type matches

**`SubjectBasedRouter`** (`org/SubjectBasedRouter.java`, 149 lines):
- Simpler token-overlap algorithm: filename tokens vs directory name + aliases + path segments
- Score = `(matching_tokens / total_file_tokens) * identity_confidence`
- Skips transient directories as destinations
- Walks workspace to depth 6 for each call (no caching)
- Used by: transient rebalance, E010 health check

**`E010Check`** (`cli/E010Check.java`, 237 lines):
- Read-only health diagnosis
- Finds media files in transient directories, files in reject-type directories
- Three severity levels: ERROR (rejects violation), WARNING (router match >= 0.4), INFO (no match)
- Batches unmatched files into per-directory INFO to avoid noise
- Integrated into `HealthCommand.runAudit()`

### 1.3 How Sync and Routing Interact

The sync and routing pipelines are coupled through the `.synthesis.md` files:

```
    SYNC (supply)                     ROUTING (demand)
    ─────────────                     ────────────────

    SyncCommand                       SweepCommand
    writes .synthesis.md              reads .synthesis.md
         │                                 │
         ▼                                 ▼
    .synthesis.md  ◄──────────────►  DirectoryIdentityRouter
    files on disk                    DirectoryScorer
         │                           SubjectBasedRouter
         │                           E010Check
         │                                 │
         │                                 ▼
         │                           MaintainCommand
         │                           rebalanceTransient()
         │                           rebalanceArchive()
         │                                 │
         │                                 │ moves files
         │                                 │ records ForwardingPointers
         │                                 ▼
         └─────────────────────────► updates .synthesis.md
                                     (movedFiles section)
```

**The feedback loop:** Rebalance moves files and records `ForwardingPointer` entries back into the source directory's `.synthesis.md`. The next `sync` run then merges these existing identities with newly discovered signals. This creates a slow feedback loop where routing decisions influence future identity.

**Ordering dependency:** Phase 3 (Sync) runs BEFORE Phase 5 (Rebalance) in the orchestrator. This means rebalance operates on freshly synced identities. But the forwarding pointers written by rebalance are only visible to the NEXT sync run, creating a one-cycle lag.

### 1.4 The `.synthesis.md` File Format

A `.synthesis.md` file is a YAML-front-matter Markdown document placed inside a directory to declare what that directory accepts.

```yaml
---
synthesis:
  accepts:
    types:
      - "marketing"
    formats:
      - "md"
      - "pdf"
      - "png"
      - "mp4"
    patterns:
      - "*campaign*"
  scope:
    level: "ORGANIZATION"
    organization: "eXOReaction"
    entity: null
  confidence: 0.6
  last_synced: "2026-02-21T10:30:00Z"
  source: "inferred from directory name"
  transient: true
  aliases:
    - "promo"
  rejects_types:
    - "video"
  moved_files:
    - file: "demo-video.mp4"
      moved_to: "eXOReaction/media/marketing/videos/"
      moved_at: "2026-02-21T10:30:00Z"
      moved_by: "rebalance"
      reason: "score 0.78"
---

Optional markdown body describing the directory purpose.
```

**Fields:**

| Field | Type | Source | Purpose |
|-------|------|--------|---------|
| `accepts.types` | `List<String>` | Vocabulary + Signals | Content types this dir accepts |
| `accepts.formats` | `List<String>` | Vocabulary + Signals | File extensions accepted |
| `accepts.patterns` | `List<String>` | Signals | Filename glob patterns |
| `scope.level` | `enum` | ScopeResolver | WORKSPACE / ORGANIZATION / ENTITY |
| `scope.organization` | `String?` | ScopeResolver | Organization name |
| `scope.entity` | `String?` | ScopeResolver | Entity/client name |
| `confidence` | `double` | Vocabulary / Signals | 0.0-1.0 confidence score |
| `last_synced` | `Instant?` | SyncCommand | Last sync timestamp |
| `source` | `String` | SyncCommand | Provenance: "manual", "config entry", "inferred from N files" |
| `transient` | `boolean` | Vocabulary only | Landing zone flag |
| `aliases` | `List<String>` | Vocabulary | Alternate names for subject matching |
| `rejects_types` | `List<String>` | Vocabulary | Hard-reject types |
| `moved_files` | `List<ForwardingPointer>` | Rebalance | Where files were moved to |

**Precedence rules for writing:**
1. `source: "manual"` -- never overwritten (unless `--force`)
2. Config sub-workspace entries -- synthesized with `source: "config entry"`, confidence 0.95
3. Inferred -- vocabulary + signals, always regeneratable

---

## 2. Design Problems

### P1: Vocabulary/Signals Merge Tension

**What the problem is:**

The merge logic in `DirectoryIdentityParser.merge()` line 238 uses OR for the transient flag:

```java
boolean transientFlag = existing.transient_() || discovered.transient_();
```

This means: once transient is set (either by vocabulary or by an existing `.synthesis.md`), it can never be unset through the normal merge pathway. The vocabulary always "wins" for transient because it contributes `true` for names like "marketing", and `true || false = true`.

The merge is called in two contexts in `SyncCommand`:
1. `merge(vocabResult, signalsIdentity)` -- vocabulary as "existing", signals as "discovered"
2. `merge(existing_on_disk, discovered)` -- on-disk identity as "existing", newly computed as "discovered"

Both uses compound. In context 1, vocabulary's `transient_=true` propagates. In context 2, the on-disk identity (which already has `transient_=true` from a previous sync) also propagates via OR.

**Why it matters:**

A directory like `eXOReaction/business/assets/marketing/` at depth 4 with 232 image files gets `transient_=true` because its leaf directory name is "marketing". The signals extractor infers confidence 0.94 (20+ files) and types=["media"], but it cannot override the vocabulary's transient flag. This directory is clearly a permanent, high-confidence storage location -- yet the system marks it as a temporary landing zone.

**What breaks:**

- E010 health check reports 232 media files as "in transient directory" (now batched per-directory, but still misleading)
- `rebalanceTransient()` considers moving files OUT of this directory (threshold 0.7 with `SubjectBasedRouter` might not trigger, but the logic is checking)
- `SubjectBasedRouter.findBestMatch()` skips transient directories as routing destinations (line 70: `if (identity.transient_()) continue`), so this 232-file permanent home is invisible to routing
- Health score degrades from spurious E010 findings

### P2: Transient Concept Scope Creep

**What the problem is:**

The transient concept was designed for top-level landing zones: `staging/`, `incoming/`, `marketing/` at shallow depths where files temporarily land before being organized. But `DirectoryNameVocabulary` matches purely on directory name -- it has no concept of where in the hierarchy a directory sits. Every directory named "marketing" anywhere in the workspace tree gets `transient_=true`.

The vocabulary currently marks three names as transient:
- `marketing` -- but `marketing/` at depth 4 inside `business/assets/` is permanent storage
- `staging` -- correct: staging areas are by definition transient
- `incoming` -- correct: incoming areas are transient

**Why it matters:**

In a typical workspace like `~/Documents`, "marketing" appears at multiple levels:
- `eXOReaction/marketing/` (depth 2) -- reasonable candidate for transient
- `eXOReaction/business/assets/marketing/` (depth 4) -- permanent media store, 232 files
- `eXOReaction/media/marketing/` (depth 3) -- permanent media store
- `Quadim/media/marketing/` (depth 3) -- permanent media store

The deeper in the tree, the more specialized and permanent a directory tends to be. A depth-4 directory with 232 files is definitively not a landing zone.

**What breaks:**

- False positives in health checks (E010 noise)
- Permanent directories excluded from `SubjectBasedRouter` destination pool (they are skipped because `transient_=true`)
- Users editing `.synthesis.md` to set `transient: false` have it overwritten on next sync (because merge uses OR, so any vocabulary match re-enables it)
- The only escape hatch is `source: "manual"`, which prevents ALL sync updates -- too blunt

### P3: Routing Score Calibration Gap

**What the problem is:**

`SubjectBasedRouter` scores are computed as:

```
score = (matching_file_tokens / total_file_tokens) * identity_confidence
```

Where `identity_confidence` comes from the `.synthesis.md` and maxes at 0.94 (from signals) or 0.6 (from vocabulary). A typical real-world file like `boardroom-blind-spots.mp4` has 3 tokens: `["boardroom", "blind", "spots"]`. Against a directory like `eXOReaction/media/marketing/videos/2024-2025/boardroom-series/` which has many path-segment tokens including "boardroom" and "series", only "boardroom" matches: `1/3 * 0.6 = 0.2`.

Even the best-case scenario -- a file with 2 tokens where both match a directory -- gives `2/2 * 0.94 = 0.94`. But files with descriptive names (which is most real files) have 3-6 tokens, and typically only 1-2 match: `1/4 * 0.6 = 0.15`, `2/5 * 0.7 = 0.28`.

Empirically observed: scores top out at ~0.56 in production.

The thresholds currently in use:
- E010 WARNING: 0.4 (lowered from original 0.8 because 0.8 never fired)
- E010 per-file WARNING: 0.4
- Transient rebalance: 0.7
- Archive rebalance (via DirectoryScorer): 0.7
- Sweep (via DirectoryScorer): 0.5

**Why it matters:**

The 0.7 threshold for `SubjectBasedRouter` in transient rebalance means it almost never triggers. The 0.4 threshold for E010 WARNING means it fires on weak matches. The calibration is drifting toward "lower thresholds to get any signal" rather than "calibrate scoring to produce meaningful scores."

**What breaks:**

- `rebalanceTransient()` is largely a no-op in production (scores rarely reach 0.7)
- E010 WARNING at 0.4 fires on coincidental token overlaps (not meaningful routing)
- `DirectoryScorer` and `SubjectBasedRouter` use different scoring algorithms for the same conceptual task (route a file to a directory), producing incomparable scores
- Operators cannot reason about what a score "means" because the same number (0.5) means very different things in each scorer

### P4: Routing Pipeline Fragmentation

**What the problem is:**

There are five distinct routing mechanisms, with overlapping responsibilities and inconsistent behavior:

| Mechanism | Scoring | Transient-aware? | Media-only? | Caches? | Threshold |
|-----------|---------|------------------|-------------|---------|-----------|
| Config rules | Glob/keyword match | No | No | N/A | Exact match |
| DirectoryIdentityRouter | DirectoryScorer (type+format+pattern+token+scope) | No | No | Yes | 0.5-0.7 |
| SubjectBasedRouter | Token overlap * confidence | Yes (skips transient dests) | Callers filter | No | 0.4-0.7 |
| RoutingHints | Learned patterns | No | No | Per-call | Synthetic 0.9 |
| E010Check | SubjectBasedRouter delegated | Yes (finds files IN transient) | Yes | No | 0.4 |

Critical inconsistencies:
1. **`DirectoryScorer` vs `SubjectBasedRouter`:** Both score files against directories, but use completely different algorithms. DirectoryScorer is richer (type, format, pattern, token, scope). SubjectBasedRouter is simpler (token overlap only). They produce non-comparable scores.
2. **Transient handling:** `SubjectBasedRouter` skips transient directories as destinations (good for routing OUT of transient). `DirectoryIdentityRouter/DirectoryScorer` has no transient awareness at all -- it might route files INTO transient directories.
3. **Media filtering:** `rebalanceTransient()` and `E010Check` only look at media files. `rebalanceArchive()` looks at all files. There is no principled reason for this asymmetry.
4. **Caching:** `DirectoryIdentityRouter` caches its candidate list. `SubjectBasedRouter` re-walks the workspace for every `findBestMatch()` call. In `rebalanceTransient()`, which calls `findBestMatch()` per file, this means N full workspace walks for N files.

**Why it matters:**

The system's routing behavior is unpredictable. A file in `staging/` gets routed by config rules. A file at workspace root gets swept to archive, then potentially rebalanced by `DirectoryIdentityRouter`. A file in a `marketing/` directory gets checked by `SubjectBasedRouter` via E010. Each mechanism might route the same file differently because their scoring algorithms disagree.

**What breaks:**

- A file might oscillate: sweep moves it to archive, rebalance moves it back, next sweep moves it again
- No single "routing decision log" shows why a file ended up where it did
- Performance: `SubjectBasedRouter` does a full `Files.walk(workspaceRoot, 6)` per file in rebalanceTransient
- Adding a new routing feature requires understanding which of the 5 mechanisms to modify

### P5: Identity Merge Semantic Confusion

**What the problem is:**

In `SyncCommand.java` lines 217-219, the first merge call is:

```java
discovered = parser.merge(vocabResult.get(), signalsIdentity);
```

The `merge()` method signature is `merge(existing, discovered)`. But here, `vocabResult` (from `DirectoryNameVocabulary.inferFromName()`) is passed as "existing" and `signalsIdentity` (from file content analysis) is passed as "discovered."

The merge semantics are: "existing wins for source, description, scope, and preserves via OR for transient." This means the vocabulary -- which has never seen the actual files in the directory -- gets "existing" priority over the signals extractor -- which has actually analyzed the directory contents.

Then in lines 249-250, a second merge happens:

```java
result = parser.merge(existing, discovered);
```

Here `existing` is the on-disk `.synthesis.md` (correct naming) and `discovered` is the result of the first merge (also correct).

**Why it matters:**

The vocabulary is a static template lookup. The signals extractor is an evidence-based analysis. By giving the vocabulary "existing" priority in the first merge, we get:
- Vocabulary's source ("inferred from directory name") persists over signals' source ("inferred from 232 files")
- Vocabulary's confidence (0.6 default) is max'd with signals (0.5-0.94), which is fine
- Vocabulary's transient flag (true for marketing) survives via OR, which is the root of P1

The naming confusion makes the code harder to reason about. A reader expects "existing" to mean "what was already on disk" and "discovered" to mean "what we just learned." But the first merge inverts this -- the vocabulary (a static lookup) is "existing" and the actual file analysis is "discovered."

**What breaks:**

- Developers maintaining this code misunderstand what "existing" and "discovered" mean in the first merge
- The source field persists as "inferred from directory name" even for directories with rich signal data
- No way to have signals override vocabulary for specific fields without restructuring the merge

### P6: Missing Graduation Mechanism

**What the problem is:**

Once a directory is marked `transient_=true`, there is no mechanism for it to "graduate" to permanent status based on observed behavior. The only escape is:
1. Manually editing the `.synthesis.md` to `transient: false` and `source: manual` (prevents all future sync updates)
2. Using `--force` flag on sync (resets everything, losing other manual customizations)

There is no middle ground. The system cannot learn that a directory that was once transient has become permanent through observed usage patterns.

**Why it matters:**

In organic workspace growth, directories often START as landing zones and BECOME permanent homes. The `marketing/` directory at depth 4 probably started with a few files and grew to 232. The system should recognize this growth pattern and graduate the directory.

**What breaks:**

- Permanent directories with many files (high-confidence signals) remain transient indefinitely
- The only workaround (manual source) is a sledgehammer that prevents ALL future sync
- Health check noise (E010) persists for directories that are clearly permanent
- Routing skips these directories as destinations (SubjectBasedRouter line 70)

### P7: Depth Blindness in Vocabulary

**What the problem is:**

`DirectoryNameVocabulary.inferFromName()` accepts only `String directoryName` -- the leaf directory name, with no path context. It cannot distinguish:
- `marketing/` at depth 1 (workspace root child)
- `business/assets/marketing/` at depth 4

Both get the exact same `IdentityTemplate`: `types=["marketing"], formats=["md","pdf","png","mp4"], confidence=0.6, transient_=true`.

**Why it matters:**

Depth is a strong signal for permanence. A depth-1 directory is a broad category and might be a landing zone. A depth-4 directory inside a well-structured tree is specific and permanent. The vocabulary ignores this entirely.

Similarly, parent context matters. A `marketing/` inside `business/assets/` is a different thing from a `marketing/` at workspace root. The parent path gives strong clues about whether the directory is a landing zone or a permanent home.

**What breaks:**

- Depth-4 directories get the same transient treatment as depth-1
- No way to add depth-aware rules without changing the vocabulary API
- The vocabulary contributes identical identity metadata regardless of where the directory sits in the tree

### P8: MEDIA_EXTENSIONS Duplication

**What the problem is:**

The `MEDIA_EXTENSIONS` set is defined in three separate locations with inconsistent content:

| Location | Extensions |
|----------|------------|
| `MaintainCommand` (line 761) | mp4, mov, avi, mkv, webm, mp3, wav, flac, ogg, aac, jpg, jpeg, png, gif, svg, bmp |
| `E010Check` (line 51) | mp4, mov, avi, mkv, webm, mp3, wav, flac, ogg, aac, jpg, jpeg, png, gif, svg, bmp |
| `DirectorySignalExtractor` (line 37) | png, jpg, jpeg, gif, mp4 (named IMAGE_VIDEO_EXTENSIONS, subset) |
| `MaintainOrchestrator` (line 544, inline) | mp4, mov, avi, mkv, webm, mp3, wav, flac, ogg, aac, jpg, jpeg, png, gif, svg, bmp |

The `DirectorySignalExtractor` uses a smaller set for type inference (>60% media threshold). The other three are identical but defined independently.

**Why it matters:**

If a new format needs to be added (e.g., `webp`, `heic`, `m4a`), it must be added in 3-4 places. Each is easy to miss.

**What breaks:**

- A file type recognized as media by `E010Check` but not by `DirectorySignalExtractor` creates inconsistency
- `webp` images, `heic` photos, and other modern formats are not recognized as media anywhere
- The three-way duplication is a maintenance burden that will inevitably drift

---

## 3. Enhanced Design Proposals

### D1: Depth-Aware Transient Detection

**Goal:** Eliminate false-positive transient marking for deep, high-confidence directories.

**The core rule:** A directory should be transient ONLY when ALL of the following are true:
1. Its name matches a transient-capable vocabulary entry ("marketing", "staging", "incoming")
2. Its depth from workspace root is <= 2
3. Its signals confidence is < 0.8 (i.e., it has fewer than ~11 files)

If ANY of these conditions fails, `transient_` should be `false`.

**Implementation:**

1. Change `DirectoryNameVocabulary.inferFromName()` signature to accept path context:

```java
public Optional<DirectoryIdentity> inferFromName(
        String directoryName,
        ScopeResolver.ResolvedScope scope,
        int depthFromRoot,              // NEW
        double signalsConfidence) {     // NEW
```

2. Add transient resolution logic:

```java
boolean effectiveTransient = template.transient_();
if (effectiveTransient) {
    // Depth guard: transient only at shallow depths
    if (depthFromRoot > 2) {
        effectiveTransient = false;
    }
    // Confidence guard: established directories are not transient
    if (signalsConfidence >= 0.8) {
        effectiveTransient = false;
    }
}
```

3. Update `SyncCommand` to compute and pass depth:

```java
int depth = workspaceRoot.relativize(dir).getNameCount();
DirectorySignalExtractor.DirectorySignals signals = extractor.extract(dir);
Optional<DirectoryIdentity> vocabResult = vocabulary.inferFromName(
        dir.getFileName().toString(), scope, depth, signals.confidence());
```

**Trade-offs:**
- (+) Eliminates the depth-4 marketing false positive immediately
- (+) Self-correcting: as directories grow in file count, they auto-graduate
- (-) Requires a 2-pass approach: extract signals BEFORE vocabulary, so vocabulary can see confidence
- (-) Changes the vocabulary API (but only SyncCommand calls it)

**Alternative (simpler):** Keep vocabulary API unchanged, add a post-merge correction in `SyncCommand`:

```java
// After merge, correct transient for deep high-confidence dirs
if (discovered.transient_() && depth > 2 && signals.confidence() >= 0.8) {
    discovered = withTransient(discovered, false);
}
```

This is less clean but zero API change.

### D2: Unified Routing Pipeline

**Goal:** Replace the 5 fragmented mechanisms with a single canonical routing algorithm that all callers use.

**Proposed architecture:**

```
                     ┌──────────────────────────┐
                     │     UnifiedRouter         │
                     │                           │
                     │  1. RoutingHints (fast)   │
                     │  2. ConfigRules (exact)   │
                     │  3. DirectoryScoring      │
                     │     (uses DirectoryScorer │
                     │      with all signals)    │
                     │                           │
                     │  Returns: RoutingDecision │
                     │  - destination            │
                     │  - score                  │
                     │  - mechanism (hint/config/ │
                     │    identity)              │
                     │  - confidence level       │
                     │  - reasoning chain        │
                     └──────────┬───────────────┘
                                │
           ┌────────────────────┼────────────────────┐
           │                    │                     │
     SweepCommand        MaintainCommand        E010Check
     (threshold 0.5)     rebalance (0.7)       (diagnosis only)
     + archive fallback
```

**Key changes:**

1. **Retire `SubjectBasedRouter`** -- fold its token-overlap logic into `DirectoryScorer` (which already has `computeFilenameTokenScore()`). The `SubjectBasedRouter` is a simpler version of what `DirectoryScorer` already does.

2. **Extend `DirectoryIdentityRouter`** to be the single entry point:

```java
public class UnifiedRouter {
    // Phase 1: check routing hints (learned patterns, score=0.9)
    // Phase 2: check config routing rules (glob+keyword, score=1.0)
    // Phase 3: score all directory candidates via DirectoryScorer

    public Optional<RoutingDecision> route(Path file, RoutingContext context) {
        // context includes: threshold, allowed mechanisms,
        // whether to skip transient destinations, media-only flag
    }
}
```

3. **Add transient-awareness to `DirectoryScorer`**: when the caller specifies "skip transient destinations," the scorer filters them out -- just as `SubjectBasedRouter` currently does.

4. **Cache the candidate list** in a shared singleton per workspace, invalidated when sync runs.

**What this eliminates:**
- `SubjectBasedRouter.java` -- absorbed into `DirectoryScorer`
- Duplicate media extension sets (centralize in one utility)
- Per-file workspace walks in rebalanceTransient
- Score incomparability (one algorithm, one score scale)

### D3: Score Calibration Overhaul

**Goal:** Make scores meaningful and thresholds sensible.

**The problem restated:** `SubjectBasedRouter` scores top at ~0.56 because `(tokens_matched / total_tokens) * confidence` naturally produces low scores for descriptive filenames. `DirectoryScorer` can reach higher scores (1.0 content + 0.64 scope) because it sums multiple signals.

**Proposed calibration:**

1. **Normalize `DirectoryScorer` output to 0.0-1.0** by treating scope bonus as a tiebreaker rather than an additive:

```java
// Current: totalScore = contentScore + scopeBonus (can exceed 1.0)
// Proposed: totalScore = contentScore + (scopeBonus * (1.0 - contentScore) * 0.5)
// This means scope can add up to 0.32 to bring a 0.5 to 0.66, but never exceed 1.0
```

2. **Establish named confidence levels** with associated thresholds:

```java
public enum RoutingConfidence {
    STRONG(0.65),      // Auto-route safe (sweep, rebalance)
    MODERATE(0.45),    // Suggest but don't auto-move (E010 WARNING)
    WEAK(0.25),        // Mention as possibility (E010 INFO with suggestion)
    NONE(0.0);         // No meaningful match

    final double threshold;
}
```

3. **Log calibration data** so thresholds can be empirically tuned:

Add to `RoutingDecision`:
```java
record RoutingDecision(
    Path destination,
    double score,
    RoutingConfidence confidence,
    String mechanism,   // "hint", "config-rule", "identity-score"
    List<String> reasons
)
```

4. **Boost token matching for short filenames:** The current algorithm penalizes files with descriptive names (many tokens, few match). Add a minimum-match bonus:

```java
// If at least 1 meaningful token matches, give a baseline
double tokenScore;
if (matches >= 2) {
    tokenScore = Math.min((double) matches / fileTokens.size() * 0.25, 0.25);
} else if (matches == 1) {
    tokenScore = 0.08;  // baseline for single-token match
}
```

### D4: Human-Transparent Routing Decisions

**Goal:** Make routing decisions legible to users through CLI output and `.synthesis.md` annotations.

**Proposal 1: Routing decision log in CLI output**

When `synthesis maintain --verbose` runs rebalance:
```
  Rebalance:
    eXOReaction/marketing/logo.png
      → eXOReaction/media/marketing/ (score 0.72, STRONG)
        reasons: type-match(+0.15), format-match(+0.2), filename-token[marketing](+0.125)
        scope-bonus: org-match(+0.24)

    eXOReaction/staging/meeting-notes-2026-02.md
      → eXOReaction/meetings/ (score 0.58, MODERATE)
        reasons: type-match(+0.3), format-match(+0.2), pattern-match[*meeting*](+0.08)

    eXOReaction/staging/random-file.dat → no match (best: 0.12, below MODERATE)
```

**Proposal 2: `synthesis route explain <file>`**

A new diagnostic subcommand that shows what the routing system would do:

```
$ synthesis route explain marketing/brand-guide.pdf

File: marketing/brand-guide.pdf
Current directory: eXOReaction/marketing/ (transient: true, confidence: 0.6)

Top 5 candidate destinations:
  1. eXOReaction/business/strategy/     score: 0.72 (STRONG)
     reasons: type-match(business,+0.3), format-match(pdf,+0.2), token[guide](+0.05)
     scope: org-match(+0.17)

  2. eXOReaction/docs/guides/           score: 0.68 (STRONG)
     reasons: type-match(guide,+0.3), format-match(pdf,+0.2), token[guide](+0.18)
     scope: org-match(+0.0)
     NOTE: AMBIGUOUS (within 0.04 of #1)

  3. eXOReaction/marketing/             score: 0.35 (WEAK)
     SKIPPED: transient directory (source, not destination)

Recommendation: HOLD — ambiguous between #1 and #2. Add alias or pattern.
```

**Proposal 3: Per-directory routing summary in `.synthesis.md`**

When sync runs, append a comment block showing the directory's routing status:

```yaml
  # Routing context (auto-generated):
  #   depth: 4
  #   file_count: 232
  #   transient_reason: "depth<=2 AND confidence<0.8" → false (depth=4)
  #   excluded_from_routing: false
  #   last_inbound: 3 files routed here in last 30 days
  #   last_outbound: 0 files routed away in last 30 days
```

### D5: Graduation Mechanism

**Goal:** Allow transient directories to graduate to permanent status based on observed behavior.

**Proposed graduation rules:**

A transient directory graduates to permanent when ANY of these conditions is met:
1. **File count threshold:** signals confidence >= 0.85 (implies 11+ files)
2. **Age threshold:** directory has existed for 90+ days AND has 5+ files
3. **Stability threshold:** no files have been routed OUT in the last 60 days AND file count has grown
4. **Manual graduation:** user sets `source: "manual"` with `transient: false`

**Implementation:**

```java
// In SyncCommand, after merge:
if (result.transient_()) {
    boolean shouldGraduate = false;
    String graduationReason = null;

    // Rule 1: High file count
    if (signals.confidence() >= 0.85) {
        shouldGraduate = true;
        graduationReason = "graduated: " + signals.fileCount() + " files (confidence "
                          + signals.confidence() + " >= 0.85)";
    }

    // Rule 2: Age + moderate files
    if (!shouldGraduate && existing != null && existing.lastSynced() != null) {
        long daysSinceCreation = Duration.between(existing.lastSynced(), Instant.now()).toDays();
        if (daysSinceCreation >= 90 && signals.fileCount() >= 5) {
            shouldGraduate = true;
            graduationReason = "graduated: " + daysSinceCreation + " days old with "
                              + signals.fileCount() + " files";
        }
    }

    // Rule 3: No recent outbound movement
    if (!shouldGraduate && existing != null) {
        long recentMoves = existing.movedFiles().stream()
                .filter(fp -> fp.movedAt() != null)
                .filter(fp -> Duration.between(fp.movedAt(), Instant.now()).toDays() <= 60)
                .count();
        if (recentMoves == 0 && signals.fileCount() >= 5) {
            shouldGraduate = true;
            graduationReason = "graduated: stable for 60+ days with " + signals.fileCount() + " files";
        }
    }

    if (shouldGraduate) {
        result = withTransient(result, false);
        result = withDescription(result,
                result.description() + "\n\n<!-- " + graduationReason + " -->");
    }
}
```

**Graduation is recorded:** The description or a new `graduation_reason` field captures why the directory graduated. This makes the decision transparent and auditable.

**Demotion:** If a graduated directory drops below 3 files AND was originally vocabulary-transient, it could be re-marked transient. But this should be conservative -- prefer stability over oscillation.

### D6: Priority-Based Merge Semantics

**Goal:** Replace OR/union merge with priority-based rules where high-confidence evidence can override lower-confidence defaults.

**Current merge rules and proposed changes:**

| Field | Current | Proposed |
|-------|---------|----------|
| `acceptsTypes` | Union | Union (keep) |
| `acceptsFormats` | Union | Union (keep) |
| `acceptsPatterns` | Union | Union (keep) |
| `scopeLevel` | Existing wins if non-default | Highest-specificity wins (keep) |
| `scopeOrg` | Existing wins if non-empty | Existing wins (keep) |
| `scopeEntity` | Existing wins if non-empty | Existing wins (keep) |
| `confidence` | Max | Max (keep) |
| `source` | Existing wins if non-empty | **Higher-confidence source wins** |
| `description` | Existing wins if non-empty | Existing wins (keep) |
| `transient_` | OR (either wins) | **Higher-confidence side wins** |
| `rejectsTypes` | Union | Union (keep) |
| `aliases` | Union | Union (keep) |
| `movedFiles` | Existing wins | Existing wins (keep) |

**Critical change -- confidence-weighted transient merge:**

```java
// Replace: boolean transientFlag = existing.transient_() || discovered.transient_();
// With:
boolean transientFlag;
if (existing.transient_() == discovered.transient_()) {
    // Agreement: use shared value
    transientFlag = existing.transient_();
} else if (existing.confidence() > discovered.confidence() + 0.1) {
    // Existing has significantly higher confidence: trust it
    transientFlag = existing.transient_();
} else if (discovered.confidence() > existing.confidence() + 0.1) {
    // Discovered has significantly higher confidence: trust it
    transientFlag = discovered.transient_();
} else {
    // Similar confidence and disagreement: default to false (conservative)
    // Rationale: marking permanent is safer than marking transient
    transientFlag = false;
}
```

**Critical change -- source field follows confidence:**

```java
// Replace: keep existing source if non-empty
// With:
String source;
if (existing.source() != null && !existing.source().isEmpty()
        && discovered.source() != null && !discovered.source().isEmpty()) {
    // Both have sources: use the higher-confidence one
    source = existing.confidence() >= discovered.confidence()
            ? existing.source() : discovered.source();
} else {
    source = (existing.source() != null && !existing.source().isEmpty())
            ? existing.source() : discovered.source();
}
```

**Context for the vocab/signals merge:** With this change, in `SyncCommand`'s first merge call `merge(vocabResult, signalsIdentity)`:
- If signals has confidence 0.94 (20+ files) and vocab has 0.6, signals wins for transient (false beats true)
- If signals has confidence 0.5 (1-3 files) and vocab has 0.6, vocab wins (true persists) -- correct for a sparse landing zone
- The 0.1 margin prevents oscillation when confidences are close

---

## 4. Recommended Next Steps

### Priority 1: Quick Wins (1-2 days each)

**1a. Fix transient merge logic (P1 + P6 partial)**

Change the OR logic in `DirectoryIdentityParser.merge()` to confidence-weighted logic (D6, transient merge section). This is a ~10-line change that immediately fixes the depth-4 marketing problem.

**File:** `DirectoryIdentityParser.java`, line 238
**Risk:** Low. Only affects transient flag resolution. All existing tests should pass because existing tests don't have confidence-disagreement scenarios for transient.
**Impact:** Immediate fix for 232-file marketing directory and all similar cases.

**1b. Add depth guard in SyncCommand (P7 + P2)**

After the vocabulary/signals merge in `SyncCommand`, add a depth guard:

```java
int depth = workspaceRoot.relativize(dir).getNameCount();
if (discovered.transient_() && depth > 2) {
    discovered = withTransient(discovered, false);
}
```

**File:** `SyncCommand.java`, after line 222
**Risk:** Very low. Only affects deep directories that were incorrectly transient.
**Impact:** Eliminates P2 and P7 for all depth-3+ directories.

**1c. Extract MEDIA_EXTENSIONS to shared constant (P8)**

Create a utility class with the canonical set:

```java
public final class MediaTypes {
    public static final Set<String> MEDIA_EXTENSIONS = Set.of(
        "mp4", "mov", "avi", "mkv", "webm",
        "mp3", "wav", "flac", "ogg", "aac",
        "jpg", "jpeg", "png", "gif", "svg", "bmp",
        "webp", "heic"  // add modern formats
    );
}
```

**Files:** New utility class + update 3 references.
**Risk:** None.

### Priority 2: Medium Effort (3-5 days)

**2a. Retire SubjectBasedRouter, unify into DirectoryScorer (P4)**

The `SubjectBasedRouter`'s token-overlap logic is already present in `DirectoryScorer.computeFilenameTokenScore()`. The difference is that `SubjectBasedRouter` ONLY uses token overlap (multiplied by confidence), while `DirectoryScorer` combines it with type, format, and pattern signals.

Steps:
1. Add transient-destination filtering to `DirectoryIdentityRouter.discoverCandidates()`
2. Add a `route()` overload that accepts a `RoutingContext` with `skipTransient` flag
3. Replace `SubjectBasedRouter` calls in `MaintainCommand.rebalanceTransient()` with `DirectoryIdentityRouter`
4. Replace `SubjectBasedRouter` calls in `E010Check` with `DirectoryIdentityRouter`
5. Delete `SubjectBasedRouter.java`

**Risk:** Medium. Requires careful threshold recalibration since `DirectoryScorer` produces different scores. Test coverage for rebalanceTransient and E010Check should be verified.

**2b. Add `synthesis route explain` diagnostic command (D4)**

A read-only command that shows how the unified router would score a file:

```
synthesis route explain path/to/file.pdf
```

This is high-value for debugging and builds user trust. Estimated 200-300 lines including formatting.

### Priority 3: Deeper Redesign (1-2 weeks)

**3a. Score calibration overhaul (D3)**

Normalize `DirectoryScorer` output to 0.0-1.0, establish named confidence levels, add calibration logging. This requires adjusting all thresholds downstream.

**3b. Full graduation mechanism (D5)**

Implement the multi-rule graduation system with tracking of graduation reasons. This requires storing graduation metadata and potentially a new field in `DirectoryIdentity`.

**3c. Full unified router (D2)**

Create `UnifiedRouter` as the single entry point for all routing decisions. This is the capstone that replaces the fragmented pipeline with a coherent model.

### Breaking Changes to `.synthesis.md` Format

None of the Priority 1 or Priority 2 changes require format changes. The `.synthesis.md` format is stable.

Priority 3 changes may want to add:
- `graduation_reason: "..."` field (additive, backward-compatible)
- `routing_context` comment block (non-semantic, backward-compatible)

The parser already handles unknown fields gracefully (ignores them), so new fields are non-breaking.

### Recommended Execution Order

```
Week 1:  1a (merge logic) + 1b (depth guard) + 1c (media set)
         → Immediately fixes the 232-file marketing problem
         → Run `synthesis sync --force` on affected workspaces
         → Verify E010 no longer reports deep permanent dirs

Week 2:  2a (retire SubjectBasedRouter)
         → Write tests comparing old vs new routing decisions
         → Calibrate thresholds for DirectoryScorer-only path

Week 3:  2b (route explain command)
         → High user-facing value, builds confidence in routing

Week 4+: 3a/3b/3c as needed based on real-world feedback
```

---

## Appendix A: Component Inventory

| File | Lines | Role |
|------|-------|------|
| `SyncCommand.java` | 535 | Orchestrates sync pipeline |
| `DirectoryIdentity.java` | 101 | 14-field record for directory metadata |
| `DirectoryIdentityParser.java` | 531 | Parse/write/merge `.synthesis.md` files |
| `DirectoryNameVocabulary.java` | 245 | Static name-to-template lookup (30+ entries) |
| `DirectorySignalExtractor.java` | 250 | Content-based directory analysis |
| `DirectoryIdentityRouter.java` | 191 | Orchestrates scoring for file routing |
| `DirectoryScorer.java` | 445 | Multi-signal file-to-directory scoring |
| `SubjectBasedRouter.java` | 149 | Token-overlap routing (candidate for retirement) |
| `ScopeChecker.java` | 65 | Scope compatibility + bonus calculation |
| `ScopeResolver.java` | 97 | Path-to-scope resolution |
| `E010Check.java` | 237 | Health check for transient/reject violations |
| `MaintainCommand.java` | 1225 | Maintenance + rebalance logic |
| `MaintainOrchestrator.java` | 933 | 9-phase pipeline orchestration |
| `SweepCommand.java` | ~450 | Root-level file sweep with routing |
| `HealthCommand.java` | 683 | Health audit with E010 integration |
| `ForwardingPointer.java` | 21 | Record for file movement tracking |
| `RoutingHints.java` | ~150 | Learned routing patterns (JSON) |

**Total subsystem:** ~6,308 lines across 17 files.

## Appendix B: Score Range Analysis

### SubjectBasedRouter (token overlap * confidence)

| Scenario | Tokens | Matches | Confidence | Score |
|----------|--------|---------|------------|-------|
| Best case: 2-token file, both match | 2 | 2 | 0.94 | 0.94 |
| Good case: 3-token file, 2 match | 3 | 2 | 0.7 | 0.47 |
| Typical: 4-token file, 1 match | 4 | 1 | 0.6 | 0.15 |
| Worst useful: 5-token file, 1 match | 5 | 1 | 0.5 | 0.10 |

**Observed production max:** ~0.56

### DirectoryScorer (multi-signal + scope)

| Scenario | Type | Format | Pattern | Token | Scope | Total |
|----------|------|--------|---------|-------|-------|-------|
| Perfect match | 0.30 | 0.20 | 0.30 | 0.25 | 0.64 | 1.64* |
| Good match | 0.30 | 0.20 | 0.00 | 0.10 | 0.24 | 0.84 |
| Moderate match | 0.15 | 0.20 | 0.00 | 0.05 | 0.00 | 0.40 |
| Weak match | 0.00 | 0.20 | 0.00 | 0.05 | 0.00 | 0.25 |

*Content capped at 1.0, so effective max is 1.0 + 0.64 = 1.64

**Key insight:** The two scorers produce numbers on completely different scales. A SubjectBasedRouter score of 0.5 is very good. A DirectoryScorer score of 0.5 is mediocre. Using the same threshold (0.7) for both, as in rebalance, means the SubjectBasedRouter path almost never fires while the DirectoryScorer path fires readily.
