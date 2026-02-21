# Synthesis: Filesystem Knowledge Graph — Vision v1.1

**Date:** 2026-02-21
**Authors:** Thor Henning Hetland (Totto) + Claude Sonnet 4.6
**Status:** Vision / North Star
**Context:** Emerged from architectural review of sync/routing subsystem

**v1.1 revisions (same session):**
- Human intent de-emphasized: contextual IA inference is primary, not human annotation
- Routing model reframed: pull/subscription (directories bid) not push/router (central decider)
- Multi-membership added: one physical home + N virtual memberships per file

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

### 3. Enrichment Is the Primary Signal — and Synthesis Knows Good IA

The current system uses file extensions and directory names as signals.
These are weak signals. The **enrich superpower** replaces them with semantic content.

When a file is enriched:
1. Its semantic signature is extracted (topics, entities, type, timeframe)
2. The centroid of its containing directory is updated (moving average)
3. The updated centroid improves routing for all future files
4. If the file's signature diverges from the centroid, it's flagged as a potential outlier

**Crucially, Synthesis does not need humans to annotate their structure.**
It brings its own knowledge of good information architecture:
- Semantic coherence (files about the same topic belong together)
- Entity coherence (files about the same people/orgs belong together)
- Temporal coherence (active work clusters by time period)
- Scope coherence (strategic vs. operational vs. archival belong at different levels)
- Type coherence (reference material, working documents, and deliverables have different homes)

These are applied *against the existing structure* — reading what's there, not imposing
what should be there. The system can see a directory with 8 files about renewable energy
and Jon Petter and understand it as a client opportunity cluster, without anyone telling
it that. It infers good (and bad) practices from evidence.

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

**Vision format** (system-derived descriptions + optional human overrides):
```yaml
---
synthesis:
  # Semantic centroid — derived from enriched files, updated each sync.
  # This is the primary layer. No human annotation required.
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
    contributing_files: 8          # physical members with enrichment
    virtual_members: 2             # files from other dirs indexed here
    last_updated: "2026-02-21T15:00:00Z"

  # Structural context — inferred by sync from path + scope
  scope:
    level: "CLIENT"
    organization: "eXOReaction"
    entity: "Tvimenning"

  # Health signals — computed each sync
  health:
    cohesion: 0.91      # how semantically tight is this cluster?
    drift: false        # is the centroid shifting over time?
    outliers: []        # files whose signature diverges from centroid

  # Human overrides — OPTIONAL. Written by human, sync-immutable.
  # Used only when the human wants to correct or constrain inference.
  # Most directories will have no overrides block at all.
  overrides:
    label: "Opportunity: Tvimenning AS — renewable energy"
    transient: false    # explicit: not a landing zone despite low depth
---
```

**Key principle**: there are now **three layers** in a `.synthesis.md`, ordered by who writes them:
- **Centroid** — system-derived, the primary layer, always present after sync
- **Health** — computed diagnostics, always present after sync
- **Overrides** — human-written, optional, corrects or constrains inference

Most directories will have no `overrides:` block. The system is designed to work well
without any human annotation. When humans do write overrides, those are respected
absolutely — but the bar for needing them should be low.

---

## Multi-Membership: One File, Many Clusters

A file has one physical home — but it may semantically belong to several directories.
This is how humans actually think about their information. A proposal about renewable
energy methodology for Tvimenning is simultaneously:

- A client document (`clients/opportunity-Tvimenning/`)
- Methodology proof (`methodology/SDD/`)
- Workshop material (`products/workshop/`)

In a filesystem, you pick one. In a knowledge graph, the file participates in multiple
clusters. Synthesis models this with two membership types:

### Physical Membership (one per file)

The file lives here. This is its primary home. The centroid of this directory includes
the file's full enrichment data as a first-class member.

### Virtual Membership (zero to many per file)

The file's enrichment data is indexed into other directories — their centroids include
it, it appears in their context, it contributes to their semantic identity — but no
physical copy is made. A bidirectional link is tracked in `.synthesis.md` on both sides.

```yaml
# In methodology/SDD/.synthesis.md:
centroid:
  contributing_files: 12    # physical members
  virtual_members: 3        # files from other dirs whose content is indexed here
  virtual_member_refs:
    - node: "clients/opportunity-Tvimenning/proposal-v2.pdf"
      relationship: "methodology application"
    - node: "clients/opportunity-Mynder/ai-security-proposal.pdf"
      relationship: "methodology application"
    - node: "media/marketing/videos/sdd-workshop-intro.mp4"
      relationship: "methodology demonstration"
```

Virtual membership is established by the routing system: when a file strongly matches
multiple clusters, the winner gets physical membership and strong runners-up get virtual
membership. The threshold for virtual membership is lower than for routing — the goal
is to enrich as many relevant centroids as possible.

### What This Enables

- **Rich centroids**: `methodology/SDD/` knows about all applications of the methodology,
  even though the application documents live in client directories
- **Cross-cluster search**: searching from `methodology/SDD/` returns results from
  all virtually-linked files, not just physical members
- **Fragmentation detection**: if 5 files about the same topic are spread across
  3 directories with virtual links between them, the system asks: should these be
  consolidated, or is the separation intentional?
- **No duplication**: one copy, many perspectives. Content stays in one place;
  the knowledge graph tracks the relationships.

---

## Routing: Pull, Not Push

### The Problem with Score-Based Push Routing

Current: Synthesis evaluates a file against all directories, picks a winner, pushes
it there. A central router that has to know about everything. Score: 0.43. Move it?

Two problems:
1. The score is uninterpretable — humans cannot reason about it or trust it
2. The router becomes a bottleneck — as the workspace grows, routing complexity grows

### The Vision: Pull-Based Subscription

Directories *register* what they want. When a file is enriched, it is *published* as
an event with its semantic signature. Directories *bid* based on how well the signature
matches their centroid. The strongest bidder wins physical membership; strong runners-up
get virtual membership.

```
File enriched → semantic signature published
                        ↓
         All directories evaluate:
         "Does this signature match my centroid?"
                        ↓
         Bids ranked by match strength
                        ↓
         Winner      → physical membership (file routed here)
         Runners-up  → virtual membership (metadata indexed here, link tracked)
         No match    → file flagged as orphan, new cluster suggested
```

**Why pull scales better than push:**
- Adding a new directory = self-registration (it declares its centroid appetite)
- The router doesn't grow in complexity as directories multiply
- Directories compete for files in their domain without a central decision-maker
- As centroid confidence grows, bidding precision improves automatically

### Every Decision Explains Itself

Every routing decision produces a **provenance chain** — a human-readable explanation
of why this file belongs here:

```
synthesis route explain eXOReaction/downloads/jon-petter-followup-2026-02-24.pdf

  Analyzing: jon-petter-followup-2026-02-24.pdf
  Enrichment: proposal, Tvimenning AS, renewable energy, Q1 2026

  Physical home: eXOReaction/clients/opportunity-Tvimenning/ (confidence: HIGH)
  Why:
    ✓ Entity match:  "Tvimenning AS" appears in 6/8 files in this directory
    ✓ Entity match:  "Jon Petter Hjulstad" appears in 4/8 files
    ✓ Topic match:   "renewable energy" is primary topic in 5/8 files
    ✓ Type match:    "proposal" fits centroid (0.87 confidence cluster)
    ✓ Timeframe:     Q1 2026 aligns with directory centroid (2025-Q4 / 2026-Q1)

  Virtual membership also offered to:
    → eXOReaction/methodology/SDD/ (MODERATE: topic match "SDD methodology")
    → eXOReaction/products/workshop/ (MODERATE: topic match "workshop delivery")

  No match: eXOReaction/business/ — only type overlap, no entity/topic alignment

  Route here with virtual links? [Y/n/edit]
```

This is trustworthy. The human understands the evidence. They can agree, disagree,
or adjust the virtual memberships — and the system learns from their choice.

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

**Drift**: A directory's centroid has shifted significantly over time.

```
[W011] Identity drift detected: eXOReaction/business/strategy/
  Centroid now:  53% marketing content, 31% client proposals, 16% strategy
  Centroid was:  91% strategy content (6 months ago)
  Signal:        18 recent files are marketing/client; 3 are strategy
  Suggest:       Review recent files — are they in the right place?
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

1. **`centroid:`** — system-derived, the primary layer, always present after sync
2. **`health:`** — computed diagnostics, always present after sync
3. **`overrides:`** — human-written, optional, corrects or constrains inference

The current `accepts:` / `rejectsTypes:` / `transient:` fields migrate into `overrides:`
as explicit human constraints. Most directories will never need an `overrides:` block.

### The Routing Pipeline Becomes Pull-Based

Directories register appetite via their centroid. Files publish signatures when enriched.
Directories bid. No central router.

All routing surfaces (staging route, maintain rebalance, E010 suggestions) use one
shared mechanism:

```
bid_strength = semantic_similarity(file_signature, directory_centroid)
```

The bid produces a confidence level (CERTAIN/HIGH/MODERATE/LOW/NONE), a reasoning
chain, and membership type (physical for winner, virtual for strong runners-up).

The threshold for automatic vs. confirmed routing is configurable per workspace.

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

### Phase 2: Contextual IA Inference + Centroid (v1.13.x)
*Connect enrichment to directory identity; add `synthesis describe`*

- When a file is enriched, update its directory's centroid block in `.synthesis.md`
- Add `centroid:` block to format (topics, entities, timeframe, doc_types, confidence)
- Routing uses centroid similarity when enrichment available (replaces token overlap)
- `synthesis describe` — read-only: what does the system understand about this directory?
- Surface confidence levels in human terms (CERTAIN/HIGH/MODERATE/LOW/NONE) in all output
- Add `overrides:` block support (sync-immutable, replaces `accepts:`/`transient:`)

### Phase 3: Pull Model + Virtual Membership (v1.14.x)
*Replace push router with pull/subscription; add multi-membership*

- Routing becomes pull-based: directories bid on enriched files
- Physical + virtual membership tracked in `centroid.virtual_member_refs`
- `synthesis route explain` shows full bid results: winner + runners-up + virtual links
- Human can accept/adjust virtual memberships at route time
- Routing hints (learned patterns) feed back into centroid bidding weights

### Phase 4: Full Knowledge Graph (v2.0)
*The north star*

- Semantic health: cohesion, drift, fragmentation, orphan cluster detection
- `synthesis graph` — visualize the knowledge graph (entity/cluster view)
- `synthesis discover` — find emerging clusters, suggest new directories
- Long-term learning: structural evolution reports, archive recommendations
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
