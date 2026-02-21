# Synthesis: Filesystem Knowledge Graph — Vision v1.0

**Date:** 2026-02-21
**Authors:** Thor Henning Hetland (Totto) + Claude Sonnet 4.6
**Status:** Vision / North Star
**Context:** Emerged from architectural review of sync/routing subsystem

---

## The One-Line Vision

Synthesis is a **filesystem-materialized knowledge graph** — not a file organizer,
not a router, but a system that understands what your files *mean*, how they relate
to each other, and helps you evolve your information architecture coherently over time.

---

## The Gap: Where We Are vs. Where We Need to Go

### What Synthesis Is Today

A heuristic classifier with routing bolted on:

```
Directory name     → vocabulary lookup → "accepts: [marketing, report]"
File extensions    → signal extraction → "formats: [md, pdf, png]"
Config rules       → explicit override → "this goes here"
                   ↓
         .synthesis.md (identity rules)
                   ↓
         Routing (score 0.43 → move here?)
```

The system asks: **"What does this directory accept?"** — a rule written in advance,
based on names and extensions.

What it cannot do:
- Understand *what* a file is actually about
- Distinguish a deliberately designed directory from an accidental accumulation
- Explain *why* a routing decision was made in human terms
- Get smarter as more files are enriched
- Detect that a directory is drifting away from its original purpose

### What Synthesis Needs to Become

A knowledge graph where directories are *emergent clusters* — not rule sets:

```
File content + metadata  →  enrichment  →  semantic signature
                                              (topics, entities, timeframe, type)
                                                         ↓
Multiple enriched files in a directory  →  cluster centroid
                                              ("renewable energy strategy, Q1 2026,
                                               Tvimenning/Jon Petter, 8 files, 0.87 confidence")
                                                         ↓
Routing = nearest-neighbor search in semantic space
Explanation = "3 other files about this topic live here"
Health = cluster cohesion + outlier detection + drift alerts
```

The system asks: **"What does this directory *represent*?"** — a description that
emerges from its contents and gets sharper as more files are enriched.

---

## The Core Concepts

### 1. Files Are Nodes

Every file in the workspace is a **node** in the knowledge graph.
A node has:
- **Raw attributes**: name, extension, size, creation date, path
- **Enriched attributes**: topics, named entities, document type, summary, timeframe,
  relationships to other files
- **Provenance**: where it came from, how it got here, what decisions were made about it

Enrichment is what elevates a file from "a PDF in a folder" to "Jon Petter's proposal
about renewable energy methodology, Q1 2026, referencing the lib-pcb workshop."

### 2. Directories Are Clusters

A directory is not a *container* — it is an **emergent community of related nodes**.

The identity of a directory is not declared upfront ("this directory accepts invoices").
It *emerges* from the collective understanding of its contents:

```
eXOReaction/clients/opportunity-Tvimenning/
  → 8 enriched files
  → common topics:  renewable energy, SDD methodology, workshop
  → key entities:   Tvimenning AS, Jon Petter Hjulstad, eXOReaction
  → timeframe:      2025-Q4 to 2026-Q2
  → document types: proposal, contract, meeting-notes, mentoring plan
  → centroid confidence: 0.87 (tight cluster)
```

This is the **cluster centroid** — the semantic center of gravity.
It has a confidence score that reflects how cohesive the cluster is.
More enriched files → tighter centroid → higher confidence → better routing.

### 3. Enrichment Is the Primary Signal

The current system uses file extensions and directory names as signals.
These are weak signals. The **enrich superpower** replaces them with semantic content.

When a file is enriched:
1. Its semantic signature is extracted (topics, entities, type, timeframe)
2. The centroid of its containing directory is updated (moving average)
3. The updated centroid improves routing for all future files
4. If the file's signature diverges from the centroid, it's flagged as a potential outlier

**The learning loop:**

```
File enriched  →  centroid updated  →  routing improves
                         ↑                     ↓
              Human accepts/rejects      Better suggestions
              routing suggestion    →    next time
```

Every enrichment makes the system smarter. Every routing decision that's accepted or
rejected is feedback. The architecture learns as it goes.

### 4. The `.synthesis.md` Evolves from Rules to Descriptions

**Current format** (rules, written in advance):
```yaml
---
synthesis:
  accepts:
    types: ["marketing", "report"]
    formats: ["md", "pdf", "png"]
  scope:
    level: "ORGANIZATION"
    organization: "eXOReaction"
  confidence: 0.7
  source: "inferred from directory name"
  transient: true
---
```

**Vision format** (descriptions, derived from contents, updated by sync):
```yaml
---
synthesis:
  # Human intent — written by human, never overwritten by sync
  intent: "Opportunity tracking for Tvimenning renewable energy partnership"

  # Semantic centroid — derived from enriched files, updated each sync
  centroid:
    topics:
      - "renewable energy"
      - "SDD methodology"
      - "workshop delivery"
    entities:
      - "Tvimenning AS"
      - "Jon Petter Hjulstad"
    timeframe: "2025-Q4 / 2026-Q1"
    document_types: ["proposal", "contract", "meeting-notes"]
    confidence: 0.87
    contributing_files: 8
    last_updated: "2026-02-21T15:00:00Z"

  # Structural context — from sync
  scope:
    level: "CLIENT"
    organization: "eXOReaction"
    entity: "Tvimenning"

  # Routing behavior — declared or learned
  routing:
    transient: false
    # How did this directory graduate from transient?
    graduation: "8 enriched files, centroid confidence 0.87, stable 60+ days"

  # Health signals — computed each sync
  health:
    cohesion: 0.91      # how semantically tight is this cluster?
    drift: false        # is the centroid drifting from initial intent?
    outliers: []        # files that don't fit the centroid
---
```

**Key principle**: there are now **three distinct layers** in a `.synthesis.md`:
- **Intent** (human-written, authoritative, never overwritten)
- **Centroid** (system-derived, updated by sync, reflects reality)
- **Health** (computed, diagnostic, surfaces problems)

A human reading the file can immediately see: what I said this is for, what it actually
contains, and whether those are aligned.

---

## Routing: From Scores to Explanations

### The Problem with Score-Based Routing

Current: a file scores 0.43 against a directory. Move it there? Maybe?

The number is uninterpretable. A human cannot reason about it. Trust is impossible.

### The Vision: Semantic Similarity with Reasoning Chains

Every routing decision produces a **provenance chain** — a human-readable explanation
of why this file belongs here:

```
synthesis route explain eXOReaction/downloads/jon-petter-followup-2026-02-24.pdf

  Analyzing: jon-petter-followup-2026-02-24.pdf
  Enrichment: proposal, Tvimenning AS, renewable energy, Q1 2026

  Best match: eXOReaction/clients/opportunity-Tvimenning/ (confidence: HIGH)

  Why:
    ✓ Entity match:  "Tvimenning AS" appears in 6/8 files in this directory
    ✓ Entity match:  "Jon Petter Hjulstad" appears in 4/8 files
    ✓ Topic match:   "renewable energy" is primary topic in 5/8 files
    ✓ Type match:    "proposal" is accepted document type (centroid: 0.87)
    ✓ Timeframe:     Q1 2026 aligns with directory centroid (2025-Q4 / 2026-Q1)

  Alternative: eXOReaction/business/ (confidence: LOW)
    - Only "proposal" type matches; no entity or topic overlap

  Route here? [Y/n]
```

This is trustworthy. The human understands the evidence. They can agree, disagree,
or ask for alternatives — and the system learns from their choice.

### Confidence Levels in Human Terms

Not 0.0–1.0. Instead:

| Level | Meaning | Routing behavior |
|-------|---------|-----------------|
| **CERTAIN** | Multiple entity + topic + type matches | Route automatically (configurable) |
| **HIGH** | Strong entity or topic match, type match | Route with single-line confirmation |
| **MODERATE** | Partial semantic overlap | Present as suggestion with reasoning |
| **LOW** | Weak or indirect match | Surface as question, show alternatives |
| **NONE** | No semantic match found | Flag as orphan, suggest creating new directory |

---

## Detecting Good and Bad Practices

One of the most valuable capabilities of a knowledge graph is **structural analysis** —
seeing patterns across the whole workspace that are invisible at the file level.

### What Good Looks Like

- **Tight clusters**: directory centroid confidence > 0.8 — files are semantically coherent
- **Clear separation**: directories have distinct centroids, low overlap
- **Consistent naming**: directory names reflect centroid topics
- **Temporal coherence**: active directories have recent content

### What Bad Looks Like (surfaced by health checks)

**Drift**: A directory's centroid has moved away from its original intent.

```
[W011] Identity drift detected: eXOReaction/business/strategy/
  Intent:   "Strategic business planning"
  Centroid: 53% marketing content, 31% client proposals, 16% strategy
  Was:      91% strategy content (6 months ago)
  Signal:   18 recent files are marketing/client; 3 are strategy
  Suggest:  Review recent files — are they in the right place?
```

**Orphan clusters**: Files that don't belong to any strong cluster.

```
[I012] 7 orphan files with no strong cluster match
  These files have no semantic home:
    - eXOReaction/downloads/ai-security-whitepaper-2026.pdf
    - eXOReaction/downloads/nora-award-2025-ceremony.mp4
    ...
  Suggest: Create eXOReaction/research/ai-security/ ?
           Or route to existing eXOReaction/clients/opportunity-Mynder/ ?
```

**Fragmentation**: The same concept is split across multiple directories.

```
[W013] Concept fragmentation: "renewable energy methodology"
  Found in 3 directories:
    - eXOReaction/clients/opportunity-Tvimenning/ (8 files, centroid: 0.87)
    - eXOReaction/business/strategy/ (3 files, centroid overlap: 0.71)
    - eXOReaction/media/marketing/videos/ (2 files, centroid overlap: 0.58)
  Suggest: Are these intentionally separate, or should they be consolidated?
```

**Over-accumulation in landing zones**: Transient directories that never route out.

```
[W014] Landing zone stale: eXOReaction/media/marketing/
  232 media files, oldest: 45 days
  Centroid emerging: product demos, AI features, LinkedIn content
  No outbound routing in 45 days — is this becoming a permanent home?
  Suggest: Either route files to specific destinations, or graduate this directory
           (mark as permanent for product-demo media)
```

---

## The Learning Model

### Short-Term Learning (per-sync)

Each time `synthesis sync` runs on a directory with enriched files:
1. Recompute centroid from enriched metadata
2. Update `.synthesis.md` centroid block
3. Flag files whose signatures diverge from the centroid
4. Update confidence (more enriched files → higher confidence)

### Medium-Term Learning (routing decisions)

Each routing decision creates a feedback record:
- File → destination → accepted/rejected by human → confidence delta
- Accepted routes reinforce the centroid (this type of file belongs here)
- Rejected routes flag a mismatch (the centroid description needs updating)

Over time, routing hints accumulate: "for files mentioning Tvimenning + renewable energy,
route to opportunity-Tvimenning/ — this has been confirmed 6 times."

### Long-Term Learning (structural evolution)

Over weeks/months, Synthesis can observe:
- Which directories are growing (emerging importance)
- Which are stagnating (candidates for archive)
- Which concepts are fragmenting (candidates for consolidation)
- Which naming conventions are consistent vs. inconsistent

This feeds into periodic reports: "Your workspace has evolved — here's what changed,
what's working, and what needs attention."

---

## The New CLI Surface

### Today

```bash
synthesis sync -d ~/Documents           # writes .synthesis.md files
synthesis health -d ~/Documents         # structural audit (rules-based)
synthesis maintain -d ~/Documents       # routes files (score-based, opaque)
staging route --enrich-first            # enriches then routes
```

### Vision

```bash
# Describe what the system understands about your workspace
synthesis describe -d ~/Documents
synthesis describe eXOReaction/clients/opportunity-Tvimenning/

# Explain a routing decision before making it
synthesis route explain path/to/file.pdf

# Route with explanation and optional confirmation
synthesis route path/to/file.pdf [--auto | --confirm | --dry-run]

# Health: cluster analysis (not just structural rules)
synthesis health -d ~/Documents
synthesis health eXOReaction/business/   # focus on a subtree

# See the knowledge graph structure
synthesis graph -d ~/Documents [--format ascii | json | mermaid]
synthesis graph --entity "Tvimenning AS"   # show everything related to an entity

# Learning feedback
synthesis feedback accept/reject [routing-id]

# Discover new structures
synthesis discover -d ~/Documents           # find emerging clusters
synthesis discover --orphans                # files with no semantic home
synthesis discover --fragmentation          # concepts split across dirs
```

---

## What Changes Architecturally

### The Enrichment Pipeline Becomes Central

Currently: enrichment is optional, used primarily for search quality.

Vision: enrichment is the **primary input** to directory identity. The sync pipeline
becomes enrichment-driven:

```
File arrives → staged → enriched → semantic signature extracted
                                          ↓
                               Find best cluster (directory)
                                          ↓
                               Route with explanation
                                          ↓
                            Update destination centroid
                                          ↓
                            Update .synthesis.md centroid block
```

### The `.synthesis.md` Format Stabilizes on Three Layers

1. **`intent:`** — human-written, immutable by sync, the source of truth for design decisions
2. **`centroid:`** — system-derived, updated by sync, reflects observed reality
3. **`health:`** — computed, updated by sync, surfaces problems

The current `accepts:` / `rejectsTypes:` / `transient:` fields are **routing hints**
that may be deprecated in favor of centroid-based routing. Or they become overrides
within the `intent:` block: "the human said transient=true, even though centroid
confidence is 0.87."

### The Routing Pipeline Unifies

All routing (staging route, maintain rebalance, E010 suggestions) uses one algorithm:

```
semantic similarity(file signature, directory centroid)
```

The algorithm produces a confidence level (CERTAIN/HIGH/MODERATE/LOW/NONE) and
a reasoning chain. The threshold for automatic vs. confirmed routing is configurable
per workspace.

### The Health Model Expands from Rules to Analytics

Current health checks: structural rules (E001 phantom paths, E002 build artifacts,
W001 empty dirs, E010 transient violations).

Vision: add semantic health:
- Cluster cohesion per directory
- Drift detection (centroid vs. intent)
- Orphan detection (files with no cluster)
- Fragmentation detection (concept in N directories)
- Landing zone health (transient dirs accumulating without routing)

---

## Implementation Path

This is a multi-release vision. The path respects existing functionality.

### Phase 1: Fix the Foundation (v1.12.x, now)
*The Opus report's Priority 1 fixes — mechanical cleanup*

- Fix transient merge logic (confidence-weighted, not OR)
- Add depth guard for vocabulary transient (depth ≤ 2)
- Unify routing pipeline (retire SubjectBasedRouter)
- Add `synthesis route explain` diagnostic command
- Extract MediaTypes constants

These fixes make the current heuristic system coherent. They don't add the knowledge
graph layer, but they clean up the architectural debt that would make it harder to build.

### Phase 2: Surface the Intent Layer (v1.13.x)
*Separate human intent from system inference in `.synthesis.md`*

- Add `intent:` block to `.synthesis.md` format (human-written, sync-immutable)
- Distinguish "declared by human" vs. "inferred by system" in all health output
- Add `synthesis describe` command (read-only: what does the system know about this dir?)
- Surface confidence levels in human terms in health output

### Phase 3: Enrich → Centroid → Route (v1.14.x)
*Connect enrichment to directory identity*

- When a file is enriched, update its directory's centroid block in `.synthesis.md`
- Add `centroid:` block to `.synthesis.md` format
- Routing uses centroid similarity (not token overlap) when enrichment available
- `synthesis route explain` shows centroid-based reasoning when available

### Phase 4: Full Knowledge Graph (v2.0)
*The north star*

- Semantic health checks: cohesion, drift, fragmentation, orphans
- Learning loop: routing feedback updates centroids and routing hints
- `synthesis graph` — visualize the knowledge graph
- `synthesis discover` — find emerging patterns
- Full `synthesis describe` — the system explains your information architecture to you

---

## Why This Matters

### For Totto's Workspace Today

The Documents workspace has 730+ directories and 8,000+ files built up over years.
That structure is **intentional** — it reflects how eXOReaction thinks about its work:
clients, products, methodology, business, marketing.

Synthesis should read that architecture, understand it, and help it evolve coherently —
not impose a new structure on top of it. When a proposal about Tvimenning arrives in
downloads, the system should know (from enrichment + centroid matching) exactly where it
belongs, explain why, and route it there with the human's trust.

### For Teams in General

Most teams have information architectures that grew organically. They work — imperfectly,
with friction, with some files that are hard to find. Synthesis should be the system that:

1. **Maps** the architecture as it exists
2. **Explains** what it sees (good and bad)
3. **Suggests** evolution paths
4. **Learns** from human decisions
5. **Gets smarter** as more content is enriched

Not a perfect filing system imposed from above. A collaborative partner that makes the
existing architecture more legible and more functional, file by file, decision by decision.

---

## The Key Insight

The filesystem IS the knowledge graph. It always was. The directories, the naming
conventions, the folder structures — these are a team's collective knowledge about
how their work is organized. They just need a system that can read that graph,
make it explicit, and help it evolve.

Synthesis is that system.

---

*Next review: after Phase 1 completion*
*Related documents:*
- *[SYNC-ROUTING-ARCHITECTURE-REPORT.md](../architecture/SYNC-ROUTING-ARCHITECTURE-REPORT.md) — current state analysis*
- *[Synthesis CLAUDE.md](../../CLAUDE.md) — codebase context*
