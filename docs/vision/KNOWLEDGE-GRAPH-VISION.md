# Synthesis: Filesystem Knowledge Graph — Vision v1.2

**Date:** 2026-02-21
**Authors:** Thor Henning Hetland (Totto) + Claude Sonnet 4.6 + Claude Opus 4.6
**Status:** Vision / North Star
**Context:** Emerged from architectural review of sync/routing subsystem

**Revision history:**
- **v1.0:** Initial vision — files as nodes, directories as clusters, enrichment as primary signal
- **v1.1:** Pull model (directories bid, not pushed), multi-membership (physical + virtual), human
  intent de-emphasized (contextual IA inference is primary)
- **v1.2:** Directories reframed as agents with *wants* (not rules). Wants = a learning mechanism
  grounded in what's already there + what should be there. Four-layer `.synthesis.md` format.
  Want-based health signals (starvation, overflow, conflict). Convergence lifecycle.

---

## The One-Line Vision

Synthesis is a **filesystem-materialized knowledge graph** — not a file organizer, not a router,
but a system that understands what your files *mean*, how they relate to each other, and helps
your information architecture evolve coherently over time — because it understands what every
part of it *wants to become*.

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
         Routing (score 0.43 → move it?)
```

The system asks: **"What does this directory accept?"** — a rule written in advance,
based on names and extensions. Directories are passive gatekeepers.

What it cannot do:
- Understand *what* a file is actually about
- Distinguish a deliberately designed directory from an accidental accumulation
- Express what a directory is *trying to accumulate* vs. what it happens to contain
- Explain *why* a routing decision was made in human terms
- Detect that a directory is drifting away from its original purpose
- Notice what's *missing* from a directory that should be there

### What Synthesis Needs to Become

A knowledge graph where directories are *agents with purpose* — not rule sets:

```
File content + metadata  →  enrichment  →  semantic signature
                                              (topics, entities, timeframe, type)
                                                         ↓
Multiple enriched files in a directory  →  cluster centroid
                                              ("renewable energy strategy, Q1 2026,
                                               GreenField Energy, 8 files, 0.87 confidence")
                                                         ↓
Directory wants: "I want more files like these"
                 "I also want an invoice — that's missing from this client cluster"
                                                         ↓
Routing = directories BID on enriched files they want
Health  = are my wants being satisfied? am I getting what I'm for?
```

The system asks: **"What does this directory *want*?"** — an expression that emerges from
its contents and grows more precise as more files arrive.

---

## The Core Concepts

### 1. Files Are Nodes

Every file in the workspace is a **node** in the knowledge graph.
A node has:
- **Raw attributes**: name, extension, size, creation date, path
- **Enriched attributes**: topics, named entities, document type, summary, timeframe,
  relationships to other files
- **Provenance**: where it came from, how it got here, what decisions were made about it

Enrichment is what elevates a file from "a PDF in a folder" to "a proposal about
renewable energy methodology, Q1 2026, referencing the lib-pcb workshop."

### 2. Directories Are Clusters

A directory is not a *container* — it is an **emergent community of related nodes**.

The identity of a directory is not declared upfront ("this directory accepts invoices").
It *emerges* from the collective understanding of its contents:

```
clients/opportunity-greenfield/
  → 8 enriched files
  → common topics:  renewable energy, SDD methodology, workshop
  → key entities:   GreenField Energy, Jane Smith
  → timeframe:      2025-Q4 to 2026-Q2
  → document types: proposal, contract, meeting-notes, mentoring plan
  → centroid confidence: 0.87 (tight cluster)
```

This is the **cluster centroid** — the semantic center of gravity.
It has a confidence score that reflects how cohesive the cluster is.
More enriched files → tighter centroid → higher confidence → more precise wants.

### 3. Directories Have Purpose and Appetite

A directory is not just a cluster — it is an **agent with a purpose**. It was created
because someone wanted to accumulate something. A `clients/opportunity-greenfield/`
directory was created because someone wanted to track the GreenField engagement.
That intent is a **want**.

Directories have wants in two forms:

#### Descriptive wants: learned from what's already there

The centroid *is* the primary wants expression. A directory with three quarterly reports
(Q1, Q2, Q3) has a centroid about quarterly reporting — and therefore *wants more quarterly
reports*. Q4 arrives and the directory bids for it. No rule was written. The want emerged
from what's already there.

```
Contents (Q1, Q2, Q3 reports)  →  centroid (quarterly reports)  →  want (Q4 report)
```

This is a **self-reinforcing learning mechanism**: as more files arrive, the centroid
tightens, the wants become more precise, and routing improves.

#### Aspirational wants: what else should be there

Beyond what's present, directories have gaps. A client directory has contracts and meeting
notes but no invoice. A project directory has design docs but no test plan. Synthesis
knows what coherent clusters of a given type should contain — and can identify what's missing.

```
Contents (contracts, meeting notes)  →  archetype: "client opportunity"
                                      →  gap: invoice, proposal (missing from archetype)
                                      →  want: "also looking for invoices and proposals"
```

This is not rule-writing — it is **archetype-based gap detection**: Synthesis applies
knowledge of known information architecture patterns to identify what would make the
cluster more complete.

#### The cold start: how an empty directory expresses wants

An empty directory has no centroid, but it may have clear wants. It was just created for a
reason. Synthesis bootstraps initial wants from multiple signals (evaluated in precedence):

| Tier | Signal | Confidence | Example |
|------|--------|-----------|---------|
| 1 | README or seed file | Moderate-high (0.5–0.7) | README describes the directory's purpose |
| 2 | Directory name inference | Low-moderate (0.2–0.4) | `opportunity-nova` → wants: Nova Corp files |
| 3 | Parent directory inheritance | Low (0.1–0.2) | Under `clients/` → wants: CLIENT-scoped content |
| 4 | Explicit override (rare) | Exact | Human writes `wants:` block directly |

An empty directory with name-inferred wants bids *weakly* — but it bids. If no other
directory wants a file about Nova Corp, the new directory wins by default. This is correct:
the directory was created for a reason.

#### The convergence lifecycle

Wants and centroid start separate and converge over time:

```
Creation:    wants = name-inferred (weak)     centroid = null           satisfaction = 0.0
Seed file:   wants = name + seed (moderate)   centroid = sparse         satisfaction = 0.3
Growing:     wants ≈ centroid (emerging)      centroid = growing        satisfaction = 0.6
Mature:      centroid IS the wants expression centroid = tight (0.87)   satisfaction = implicit
Drifting:    wants ≠ centroid (diverging)     centroid = shifted        satisfaction = declining
Corrected:   wants = human-stated purpose     centroid = recalculating  satisfaction = improving
```

For a **mature directory**, the `wants:` block is absent — the centroid is the wants
expression and the distinction is invisible. For a **new directory** or **drifting directory**,
the `wants:` block makes the purpose explicit and measurable.

### 4. Enrichment Is the Primary Signal — and Synthesis Knows Good IA

The current system uses file extensions and directory names as signals.
These are weak signals. The **enrich superpower** replaces them with semantic content.

When a file is enriched:
1. Its semantic signature is extracted (topics, entities, type, timeframe)
2. The centroid of its containing directory is updated (moving average)
3. The updated centroid improves routing for all future files
4. If the file's signature diverges from the centroid, it's flagged as a potential outlier
5. The directory's wants are updated — descriptive (tighter centroid) and aspirational
   (archetype gaps rechecked with the new content)

**Crucially, Synthesis does not need humans to annotate their structure.**
It brings its own knowledge of good information architecture:
- Semantic coherence (files about the same topic belong together)
- Entity coherence (files about the same people/orgs belong together)
- Temporal coherence (active work clusters by time period)
- Scope coherence (strategic vs. operational vs. archival belong at different levels)
- Type coherence (reference material, working documents, and deliverables have different homes)
- **Completeness coherence** (a client cluster without invoices is incomplete)

These are applied *against the existing structure* — reading what's there, inferring
what it means, and identifying what's missing. No human annotation required.

**The learning loop:**

```
File enriched  →  centroid updated  →  wants refined
                         ↑                     ↓
              Human accepts/rejects      Better routing
              routing suggestion    →    next time
```

Every enrichment makes the system smarter. Every routing decision is feedback.

### 5. The `.synthesis.md` Evolves from Rules to Purpose

**Current format** (rules, written in advance):
```yaml
---
synthesis:
  accepts:
    types: ["marketing", "report"]
    formats: ["md", "pdf", "png"]
  scope:
    level: "ORGANIZATION"
  confidence: 0.7
  source: "inferred from directory name"
  transient: true
---
```

**Vision format** (four layers, serving different purposes):
```yaml
---
synthesis:
  # LAYER 1: Semantic centroid — what this directory IS.
  # System-derived from enriched files. Updated each sync.
  # This is the primary layer. Always present after first enrichment.
  centroid:
    topics:
      - "renewable energy"
      - "SDD methodology"
      - "workshop delivery"
    entities:
      - "GreenField Energy"
      - "Jane Smith"
    timeframe: "2025-Q4 / 2026-Q1"
    document_types: ["proposal", "contract", "meeting-notes"]
    confidence: 0.87
    contributing_files: 8          # physical members with enrichment
    virtual_members: 2             # files from other dirs indexed here
    last_updated: "2026-02-21T15:00:00Z"

  # LAYER 2: Wants — what this directory is TRYING TO BECOME.
  # Present in two scenarios:
  #   a) Cold start: centroid is absent/weak, wants bootstrapped from name/seed
  #   b) Drift: wants diverge from centroid, capturing original purpose
  # Absent in mature directories — centroid IS the wants expression.
  # satisfaction: how well current content matches stated wants (0.0–1.0).
  wants:
    topics: ["GreenField opportunity lifecycle", "renewable energy"]
    entities: ["GreenField Energy", "Jane Smith"]
    also_looking_for: ["invoice", "mentoring contract"]   # aspirational gaps
    source: "inferred from directory name + 8 files"
    satisfaction: 0.87

  # LAYER 3: Structural context — inferred by sync from path + scope
  scope:
    level: "CLIENT"
    organization: "myorg"
    entity: "GreenField"

  # LAYER 4: Health signals — computed each sync
  health:
    cohesion: 0.91      # how semantically tight is this cluster?
    drift: false        # wants vs centroid divergence
    outliers: []        # files whose signature diverges from centroid

  # OVERRIDES: Human corrections — OPTIONAL. Sync-immutable.
  # Used only when inference needs correction or hard constraints apply.
  # Most directories will have no overrides block.
  overrides:
    label: "Opportunity: GreenField Energy — renewable energy"
    rejects_types: []   # hard rejection (not soft preference)
    transient: false
---
```

**Key principle**: The four layers serve distinct purposes:
- **Centroid** — what the directory IS (empirical, backward-looking)
- **Wants** — what the directory is TRYING TO BECOME (aspirational, forward-looking)
- **Health** — diagnostic state computed from centroid + wants alignment
- **Overrides** — rare human corrections, absolutely respected

The `wants:` block is **temporary in most directories**: it bootstraps on creation, evolves
as files arrive, and disappears once the centroid has converged with the stated purpose.
Most mature directories will have no `wants:` block — the centroid speaks for itself.

**What a cold-start directory looks like:**
```yaml
---
synthesis:
  # No centroid yet — no enriched files
  wants:
    topics: ["Nova Corp", "CTO partnership", "cloud infrastructure"]
    entities: ["Nova Corp"]
    source: "inferred from directory name: opportunity-nova"
    satisfaction: 0.0
  scope:
    level: "CLIENT"
    organization: "myorg"
  health:
    status: "bootstrapping"
    file_count: 0
---

New opportunity directory for Nova Corp CTO partnership.
```

---

## Multi-Membership: One File, Many Clusters

A file has one physical home — but it may semantically belong to several directories.
This is how humans actually think about their information. A proposal about renewable
energy methodology is simultaneously:

- A client document (`clients/opportunity-greenfield/`)
- Methodology proof (`methodology/sdd/`)
- Workshop material (`products/workshop/`)

In a filesystem, you pick one. In a knowledge graph, the file participates in multiple
clusters. Synthesis models this with two membership types:

### Physical Membership (one per file)

The file lives here. This is its primary home. The centroid of this directory includes
the file's full enrichment data as a first-class member. The directory *wanted* this file
and won the bid.

### Virtual Membership (zero to many per file)

The file's enrichment data is indexed into other directories — their centroids include
it, it appears in their context, it contributes to their semantic identity — but no
physical copy is made. A bidirectional link is tracked in `.synthesis.md` on both sides.

```yaml
# In methodology/sdd/.synthesis.md:
centroid:
  contributing_files: 12    # physical members
  virtual_members: 3        # files from other dirs whose content is indexed here
  virtual_member_refs:
    - node: "clients/opportunity-greenfield/proposal-v2.pdf"
      relationship: "methodology application"
    - node: "clients/opportunity-secura/ai-security-proposal.pdf"
      relationship: "methodology application"
    - node: "media/marketing/videos/sdd-workshop-intro.mp4"
      relationship: "methodology demonstration"
```

Virtual membership is established by the routing system: when a file strongly matches
multiple clusters, the winner gets physical membership and strong runners-up get virtual
membership. Virtual membership also satisfies the wants of runner-up directories — the
`methodology/sdd/` directory *wanted* to know about SDD applications in client work,
and virtual membership delivers that without creating duplicates.

### What This Enables

- **Rich centroids**: `methodology/sdd/` knows about all applications of the methodology,
  even though the application documents live in client directories
- **Cross-cluster search**: searching from `methodology/sdd/` returns results from
  all virtually-linked files, not just physical members
- **Want satisfaction across directories**: a methodology directory's aspirational want
  ("I want examples of methodology applied to real clients") is satisfied virtually
- **No duplication**: one copy, many perspectives

---

## Routing: Pull, Not Push

### The Problem with Score-Based Push Routing

Current: Synthesis evaluates a file against all directories, picks a winner, pushes
it there. A central router that has to know about everything. Score: 0.43. Move it?

Two problems:
1. The score is uninterpretable — humans cannot reason about it or trust it
2. The router becomes a bottleneck — as the workspace grows, routing complexity grows

### The Vision: Directories Bid Because They Want Files

Directories *register* what they want (via their centroid). When a file is enriched, it
is *published* as an event with its semantic signature. Directories *bid* based on how
strongly their wants align with the file's signature. The strongest bidder wins physical
membership; strong runners-up get virtual membership.

```
File enriched → semantic signature published
                        ↓
         All directories evaluate:
         "Does this match what I want?"
                        ↓
         Bids ranked by want-alignment strength
                        ↓
         Winner      → physical membership (directory WANTED this file most)
         Runners-up  → virtual membership  (still wanted it, secondarily)
         No match    → file flagged as orphan, new cluster suggested
```

**Why pull scales better than push:**
- Adding a new directory = self-registration (it expresses its wants via centroid)
- The router doesn't grow in complexity as directories multiply
- Cold-start directories bid weakly but participate from day one
- As centroid confidence grows, bidding precision improves automatically
- Want starvation becomes visible: a directory that stops winning bids may need attention

### Every Decision Explains Itself

Every routing decision produces a **provenance chain** — a human-readable explanation
of why this file belongs here:

```
synthesis route explain downloads/jane-smith-followup.pdf

  Analyzing: jane-smith-followup.pdf
  Enrichment: proposal, GreenField Energy, renewable energy, Q1 2026

  Physical home: clients/opportunity-greenfield/ (confidence: HIGH)
  Why this directory wants this file:
    ✓ Entity match:  "GreenField Energy" — present in 6/8 existing files
    ✓ Entity match:  "Jane Smith" — present in 4/8 existing files
    ✓ Topic match:   "renewable energy" — primary topic in 5/8 existing files
    ✓ Type match:    "proposal" fits centroid (0.87 confidence cluster)
    ✓ Timeframe:     Q1 2026 aligns with directory centroid (2025-Q4 / 2026-Q1)
    ✓ Aspirational:  Directory wants "proposal" — currently missing (gap filled!)

  Virtual membership also offered to:
    → methodology/sdd/ (MODERATE: topic match "SDD methodology")
    → products/workshop/ (MODERATE: topic match "workshop delivery")

  No match: business/strategy/ — only type overlap, no entity/topic alignment

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

## Detecting Good, Bad, and Missing

One of the most valuable capabilities of a knowledge graph is **structural analysis** —
seeing patterns across the whole workspace that are invisible at the file level.
With the "wants" model, Synthesis can detect not just what's wrong but what's *missing*.

### Want-Based Health Signals

**Want fulfillment** (I020): The directory is getting what it wants.

```
[I020] Want fulfillment: clients/opportunity-greenfield/
  Wants: renewable energy, GreenField Energy, workshop delivery
  Satisfaction: 0.87 (HIGH)
  Recent: 3 files arrived in last 30 days matching wants
  Status: Healthy — directory is accumulating relevant content
```

**Want starvation** (W020): A directory has clear wants but nothing is arriving.

```
[W020] Want starvation: clients/opportunity-nova/
  Wants: Nova Corp, CTO partnership, cloud infrastructure (since: 2026-02-15)
  Satisfaction: 0.0 (NONE) — file count: 0
  Days since creation: 6
  Signal: Directory was created but nothing has arrived.
  Suggest: Is this opportunity still active?
           Or are related files landing elsewhere?
```

*Note: Only expressible under the "wants" model. Under "accepts," an empty directory
is just empty. Under "wants," it is starving — a purpose that is not being served.*

**Want overflow / centroid drift** (W021): The directory is attracting the wrong content.

```
[W021] Want drift: business/strategy/
  Wants: business strategy, competitive analysis, market positioning
  Centroid now: 53% marketing content, 31% proposals, 16% strategy
  Satisfaction: 0.31 (LOW — significant drift)
  Recent inbound: 18 marketing files in 30 days
  Signal: Content arriving doesn't match directory purpose.
  Suggest: Route marketing files to media/marketing/ ?
           Or update overrides.label to reflect the new reality?
```

**Want conflict** (I021): Multiple directories are competing for the same files.

```
[I021] Want conflict: "SDD methodology for renewable energy"
  Wanted by:
    - clients/opportunity-greenfield/ (0.82 bid strength)
    - methodology/sdd/ (0.79 bid strength)
    - products/workshop/ (0.61 bid strength)
  Affected files: 4 (currently scattered without virtual links)
  Suggest: Physical → opportunity-greenfield; virtual links → methodology/ and workshop/?
```

### Structural Health Signals (existing, evolved)

**Drift** (W011): A directory's centroid has shifted significantly over time.

```
[W011] Identity drift detected: business/strategy/
  Centroid now:  53% marketing content, 31% client proposals, 16% strategy
  Centroid was:  91% strategy content (6 months ago)
  Signal:        18 recent files are marketing/client; 3 are strategy
  Suggest:       Review recent files — are they in the right place?
```

**Orphan clusters** (I012): Files that don't belong to any strong cluster.

```
[I012] 7 orphan files with no strong cluster match
  Suggest: Create research/ai-security/ ?
           Or route to existing clients/opportunity-secura/ ?
```

**Fragmentation** (W013): The same concept is split across multiple directories without
virtual links connecting them (resolved conflicts are OK; unresolved are not).

```
[W013] Concept fragmentation: "renewable energy methodology"
  Found in 3 directories without virtual links (12 files, no cross-referencing)
  Suggest: Route to one primary directory; create virtual links for the others?
```

**Landing zone stagnation** (W014): Transient directories that never route out.

```
[W014] Landing zone stale: media/marketing/
  232 media files, oldest: 45 days — no outbound routing in 45 days
  Centroid emerging: product demos, AI features, social media content
  Suggest: Either route files out, or graduate this directory to permanent status
```

---

## The Learning Model

### Short-Term Learning (per-sync)

Each time `synthesis sync` runs on a directory with enriched files:
1. Recompute centroid from enriched metadata
2. Update `.synthesis.md` centroid block
3. Recheck aspirational wants: which gaps still exist in the cluster archetype?
4. Update `wants.satisfaction` (centroid vs. wants alignment)
5. Flag files whose signatures diverge from the centroid as potential outliers
6. Remove `wants:` block if satisfaction is high and centroid is stable (maturity)

### Medium-Term Learning (routing decisions)

Each routing decision creates a feedback record:
- File → destination → accepted/rejected by human → confidence delta
- Accepted routes reinforce the directory's wants (this type of file belongs here)
- Rejected routes flag a wants mismatch (this directory's appetite was wrong)

Over time, routing hints accumulate: "for files mentioning GreenField + renewable energy,
route to opportunity-greenfield/ — confirmed 6 times." The wants become more precise.

### Long-Term Learning (structural evolution + gap detection)

Over weeks/months, Synthesis can observe:
- **Want fulfillment trends**: which directories are getting what they want (growing well)
- **Persistent starvation**: directories whose wants have never been met (dead opportunities?)
- **Aspirational gaps that keep appearing**: "client directories consistently lack invoices" →
  surface this as a workspace-level pattern, not just per-directory
- **Naming convention coherence**: are directory names reflecting their centroid?
- **Archetype coverage**: the workspace as a whole — what kinds of clusters are present vs.
  what would you expect for an organization of this type?

This feeds into periodic reports: "Your workspace has evolved — here's what changed,
what's being fulfilled, what's missing, and what needs attention."

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
# Describe what the system understands about a directory or workspace
synthesis describe -d ~/Documents
synthesis describe clients/opportunity-greenfield/
# Output: "This directory wants to track the full lifecycle of the GreenField
# opportunity. Currently 87% satisfied: has proposals, contracts, meeting notes.
# Missing: mentoring contract (aspirational gap). 2 virtual members from sdd/."

# Explain a routing decision before making it
synthesis route explain path/to/file.pdf
# Output: shows which directories want this file and why, with bid strengths

# Route with explanation and optional confirmation
synthesis route path/to/file.pdf [--auto | --confirm | --dry-run]

# Health: cluster analysis + want-based signals
synthesis health -d ~/Documents
synthesis health business/   # focus on a subtree
# Output: structural signals + want starvation + drift + fulfillment

# See the knowledge graph structure
synthesis graph -d ~/Documents [--format ascii | json | mermaid]
synthesis graph --entity "GreenField Energy"   # show everything related to an entity

# Learning feedback
synthesis feedback accept/reject [routing-id]

# Discover new structures + gaps
synthesis discover -d ~/Documents           # find emerging clusters
synthesis discover --orphans                # files with no semantic home
synthesis discover --fragmentation          # concepts split across dirs
synthesis discover --gaps                   # aspirational gaps across workspace
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
                               Directories bid (want-based)
                                          ↓
                               Winner: physical membership
                               Runners-up: virtual membership
                                          ↓
                            Update destination centroid + wants
                                          ↓
                            Update .synthesis.md (all four layers)
```

### The `.synthesis.md` Format Stabilizes on Four Layers

1. **`centroid:`** — what the directory IS (system-derived, always present after sync)
2. **`wants:`** — what the directory is TRYING TO BECOME (optional, present for new/drifting dirs)
3. **`health:`** — computed diagnostics, always present after sync
4. **`overrides:`** — human-written, optional, hard constraints and corrections

The current `accepts:` / `rejectsTypes:` / `transient:` fields migrate to `overrides:`
as explicit human constraints. Most directories will never need an `overrides:` block.

### The Routing Pipeline Becomes Pull-Based

Directories register appetite via their centroid + wants. Files publish signatures when
enriched. Directories bid. No central router.

All routing surfaces (staging route, maintain, E010 suggestions) use one shared mechanism:

```
bid_strength = semantic_similarity(file_signature, directory_wants)
```

For mature directories: `wants = centroid`. For cold-start directories: `wants = bootstrapped
from name/seed`. The bid produces a confidence level (CERTAIN/HIGH/MODERATE/LOW/NONE),
a reasoning chain ("why I want this file"), and membership type (physical for winner,
virtual for strong runners-up).

### The Health Model Expands from Rules to Analytics

Current health checks: structural rules (E001 phantom paths, E002 build artifacts,
W001 empty dirs, E010 transient violations).

Vision: add semantic health:
- Want-based: starvation, fulfillment, drift, conflict
- Cluster-based: cohesion, outlier detection, fragmentation
- Structural: temporal coherence, naming consistency, archetype coverage

---

## Implementation Path

This is a multi-release vision. The path respects existing functionality.

### Phase 1: Fix the Foundation (v1.12.x, now)
*Mechanical cleanup — make the current heuristic system coherent*

- Fix transient merge logic (confidence-weighted, not OR)
- Add depth guard for vocabulary transient (depth ≤ 2)
- Unify routing pipeline (retire SubjectBasedRouter, one mechanism)
- Add `synthesis route explain` diagnostic command
- Extract MediaTypes constants

### Phase 2: Centroid + Wants Bootstrap (v1.13.x)
*Connect enrichment to directory identity; introduce "wants" at the cold-start level*

- When a file is enriched, update its directory's centroid block in `.synthesis.md`
- Add `centroid:` block to format (topics, entities, timeframe, doc_types, confidence)
- Add `wants:` block bootstrap from directory name (Tier 2 cold start)
- Routing uses centroid similarity when enrichment available (replaces token overlap)
- `synthesis describe` — what does the system understand? What does this directory want?
- Surface confidence levels in human terms (CERTAIN/HIGH/MODERATE/LOW/NONE)
- Add `overrides:` block support (sync-immutable, replaces `accepts:`/`transient:`)

### Phase 3: Pull Model + Virtual Membership (v1.14.x)
*Directories bid on enriched files; wants fully drive routing*

- Routing becomes pull-based: directories bid on enriched files they want
- Physical + virtual membership tracked in `centroid.virtual_member_refs`
- `synthesis route explain` shows full bid results: winner + runners-up + virtual links
- Human can accept/adjust virtual memberships at route time
- `wants.satisfaction` metric computed per sync
- Want starvation (W020) and drift (W021) health signals
- Routing hints (learned patterns) feed back into centroid bidding weights

### Phase 4: Full Knowledge Graph (v2.0)
*The north star*

- Aspirational gap detection (archetype-based: "this client cluster is missing an invoice")
- Want conflict (I021) and fulfillment (I020) health signals
- `synthesis graph` — visualize the knowledge graph (entity/cluster view)
- `synthesis discover --gaps` — workspace-level gap patterns
- Long-term learning: structural evolution reports, starvation trends, archetype coverage
- Full `synthesis describe` — the system explains your information architecture to you

---

## Why This Matters

### For a Developer's Workspace

A workspace with hundreds of directories and thousands of files built up over years contains
**intentional structure** — it reflects how a team thinks about its work: clients, products,
methodology, business, marketing. Every directory was created because someone wanted to
accumulate something there.

Synthesis should read that architecture, understand what each part *wants*, and help it
evolve coherently. When a proposal arrives in downloads, the system should know — from
enrichment + centroid + want-alignment — exactly where it belongs, explain why that
directory wants it, and route it there with the human's trust.

### For Teams in General

Most teams have information architectures that grew organically. They work — imperfectly,
with friction, with some files that are hard to find, and some directories that have drifted
from their original purpose. Synthesis should be the system that:

1. **Reads** the existing architecture and understands what each part wants
2. **Explains** what it sees (fulfilled, starving, drifting, complete)
3. **Detects** what's missing (aspirational gaps, want conflicts, orphan files)
4. **Routes** files to where they belong because it knows who wants them
5. **Learns** from human decisions to get better at understanding wants over time

Not a perfect filing system imposed from above. A collaborative partner that makes the
existing architecture more legible, more complete, and more functional — because it
understands purpose, not just structure.

---

## The Key Insight

The filesystem IS the knowledge graph. It always was. The directories, the naming
conventions, the folder structures — these are a team's collective knowledge about
how their work is organized. They express what each part of the organization *wants to accumulate*.

Synthesis is the system that reads those wants, makes them explicit, identifies where
they're being satisfied and where they're starving, detects what's missing from the pattern,
and gets smarter about all of it with every enriched file.

**The filesystem is not a cabinet with labels. It's a collection of agents with purposes.**
Synthesis is the system that understands those purposes and helps them be fulfilled.

---

*Next review: after Phase 2 implementation begins*
*Related documents:*
- *[SYNC-ROUTING-ARCHITECTURE-REPORT.md](../architecture/SYNC-ROUTING-ARCHITECTURE-REPORT.md) — current state analysis*
- *[Synthesis CLAUDE.md](../../CLAUDE.md) — codebase context*
