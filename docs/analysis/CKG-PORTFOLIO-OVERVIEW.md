# CKG Portfolio Overview — All Workspaces

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Analyst:** Thor Henning Hetland + Claude

Cross-workspace summary of the Code Knowledge Graph run across all eXOReaction-related codebases.
5 workspaces, 6 distinct codebases (exoreaction contains 10 projects).

---

## Workspace Documents

| Workspace | Document |
|-----------|----------|
| Synthesis | [CKG-DOGFOODING-FINDINGS.md](./CKG-DOGFOODING-FINDINGS.md) |
| Elprint | [CKG-ELPRINT-FINDINGS.md](./CKG-ELPRINT-FINDINGS.md) |
| Quadim | [CKG-QUADIM-FINDINGS.md](./CKG-QUADIM-FINDINGS.md) |
| Cantara | [CKG-CANTARA-FINDINGS.md](./CKG-CANTARA-FINDINGS.md) |
| eXOReaction (portfolio) | [CKG-EXOREACTION-FINDINGS.md](./CKG-EXOREACTION-FINDINGS.md) |

---

## ⚠ Reliability Warning (Issue #232 — Repo Isolation Missing)

**Multi-repo workspace analysis is unreliable until #232 is fixed.**

The CKG uses `(workspace_path, package_name)` as package identity — there is no repo dimension.
When multiple repos share the same Java package namespace (e.g., 38 Quadim microservices all using
`ai.quadim.api.*`), classes from all repos are merged into a single package node.

| Workspace | Packages Spanning >1 Repo | Impact |
|-----------|---------------------------|--------|
| Quadim | **52% (114/216 packages)** | 87% of cycles likely false positives |
| Cantara | 15% (117/735 packages) | Moderate impact on shared Whydah packages |
| exoreaction | 14% (57/384 packages) | Moderate impact |
| Synthesis (single repo) | 0% | **Unaffected** ✅ |
| Elprint (distinct namespaces) | Low | Mostly unaffected ✅ |

Concrete consequence: `ai.quadim.api.service` shows 86 files and fan-out 46 — it is actually
~25 separate repos × 3-4 files each, each individually clean. The 47 Quadim cycles detected
are almost certainly cross-repo package merging artifacts, not real architectural problems.

**Treat all cycle, fan-in/fan-out, hotspot, and god-package findings for Quadim, Cantara, and
exoreaction as provisional until #232 is resolved.**

Synthesis-on-itself analysis remains fully valid.

---

## Comparative Metrics (all post-fix, v1.13.1)

| Metric | Synthesis | Elprint | Quadim | Cantara | exoreaction |
|--------|-----------|---------|--------|---------|-------------|
| **Repos** | 1 | 5 | 38 | 54 | 33 |
| **Files** | 501 | 1,194 | 2,771 | 4,273 | 3,057 |
| **Packages** | 31 | 85 | 222 | 746 | 386 |
| **Dependencies** | 4,036 | 11,723 | 27,908 | 36,964 | 24,624 |
| **External deps** | 3,265 | 9,283 | 20,612 | 23,919 | 18,773 |
| **Cross-format links** | 141 | 7,312 | 52,290 | 8,004 | 847 |
| **Circular deps** | 2 | 22 | 47 | 128 | 83 |
| **Health signals** | 6 | 56 | 49 | 163 | 88 |
| **Hotspots** | 0 | 0 | 7 | 37 | 6 |
| **Extraction time** | 33s | 120s | 252s | 289s | 205s |
| **Architecture quality** | ★★★★★ | ★★★☆☆ | ★★★★☆ | ★★☆☆☆ | ★★★☆☆ |

---

## Architecture Quality Ranking

### ★★★★★ Synthesis (self)
- 2 cycles (both known, both fixable)
- 0 hotspots
- Clean 4-tier layer structure: db/util/core → services → features → cli/mcp/lsp
- Designed from scratch with architecture in mind — the "best case" baseline

### ★★★★☆ Quadim
- 47 cycles (concentrated in config hub and service layer)
- 7 hotspots (god controller fan-out: 40 is worst)
- **Strength:** DTO foundation layer is exceptionally clean (overlord fan-in: 43, instability: 0.04)
- **Weakness:** `api.config` involved in 5 cycles — classic Spring Boot accumulation problem

### ★★★☆☆ Elprint
- 22 cycles (all centered on `service` god-class)
- 0 hotspots (service instability 0.61 — below 0.7 threshold)
- **Strength:** Domain layer well-decomposed into feature sub-services
- **Weakness:** `service` (127 files, 7 cycles) + `entity ↔ repository` (111 edges)

### ★★★☆☆ eXOReaction (portfolio)
- 83 cycles across 10 projects (8-10 per project on average)
- 6 hotspots
- **Strength:** lib-pcb (★★★★☆), CatalystOne and Aurora (★★★☆☆)
- **Weakness:** Portfolio average dragged down by Aurora PoC (messy) and Whydah SSO fragments

### ★★☆☆☆ Cantara
- 128 cycles (highest of all workspaces)
- 37 hotspots (highest of all workspaces)
- **Strength:** Reactive services layer (disruptor, jaxrs) is clean
- **Weakness:** Whydah IAM is the architectural debt concentration point — 40+ cycles, all top hotspots

---

## Universal Patterns Across All Codebases

### 1. Config Package Circular Dep Hub (5/5 codebases)

Every codebase has a `config` package involved in circular dependencies:

| Codebase | Config cycle | Edges |
|----------|-------------|-------|
| Synthesis | `config ↔ core` | 3 + 10 |
| Elprint | `security.config ↔ service` | 4 + 14 |
| Quadim | `api.config ↔ api.service` | 5 + 32 |
| Cantara | `whydah.commands.config ↔ util` | multiple |
| exoreaction | `aurorapoc.datagenerator.config ↔ calculators` | 3 + 36 |

**Root cause:** Spring `@Configuration` classes need to wire service beans, and service beans
use config types. This creates a mutual import cycle that's hard to avoid in Spring Boot
without careful interface extraction.

**Fix pattern:** Extract shared types (constants, interfaces, records) from config into a
`model` or `domain` package that neither side imports from the other.

### 2. God Service Anti-Pattern (4/5 codebases)

Every non-Synthesis application-layer codebase has a god service:

| Codebase | God Package | Files | Fan-out | Assessment |
|----------|------------|-------|---------|------------|
| Elprint | `service` | 127 | 31 | Worst — 7 cycles centered here |
| Quadim | `api/controller` | ? | 40 | Entry point doing service work |
| Cantara | `net/whydah/service` | ? | 27 | Whydah service hub (hotspot) |
| exoreaction | `pcb/app/service` | ? | 33 | lib-pcb-app orchestrator |

**Root cause:** Services grow by accretion. Each new feature adds methods to the existing
service rather than creating a new bounded-context service. Eventually the service
orchestrates everything and its instability rises until it's a hotspot.

**Fix pattern:** Split by domain feature (component, inventory, project, BOM, user). Each
bounded-context service knows only its own domain sub-services.

### 3. Parse/Model Coupling in Format Libraries

lib-pcb (across both Cantara and exoreaction workspaces):

```
mif/parser ↔ mif/features   (2 + 65 edges) ← heaviest in portfolio
mif/parser ↔ mif/shapes     (3 + 54 edges)
gerber ↔ gerber/parser      (6 + 1 edges)
kicad ↔ kicad/converter     (1 + 9 edges)
validator ↔ validators      (3 + 33 edges)
```

This coupling is **inherent to format parsing** — the parser needs to create model objects,
and model objects often need parser context for validation. Not all cycles are bugs.
The MIF format (features + shapes + parser + writer all entangled) reflects the complexity
of the format, not poor design.

### 4. Whydah SSO Debt is Portfolio-Wide

Whydah SSO (Cantara) appears as a dependency in:
- Cantara workspace: primary home (40+ cycles, top hotspots)
- exoreaction workspace: `1881-SSOLoginWebApp` and `net.whydah.sso.*`
- Quadim workspace: `ai/quadim/api/security/whydah` (hotspot, fan-out: 10)

Whydah's architectural debt (auth/dao/utils entanglement, identity/dataimport cycles)
cascades across all consumer projects. Fixing Whydah would improve the quality score
of three workspaces simultaneously.

### 5. Stable Dependencies Principle Holds at the Macro Level

Despite the cycles within each codebase, the cross-workspace dependency direction is correct:

```
Quadim → Whydah SSO (Cantara)    ✓ application → auth library
lib-pcb-app → lib-pcb (Cantara)  ✓ application → library
Aurora (production) → Aurora PoC  ✗ production depends on PoC (reversed!)
```

The Aurora PoC → Aurora production reverse dependency is a risk: if PoC code is imported
by production Aurora, PoC instability bleeds into production.

---

## Cross-Format Link Patterns

| Workspace | Cross-format links | Pattern |
|-----------|-------------------|---------|
| Synthesis | 141 | SQL migrations → Java (Flyway V1-V13) |
| Elprint | 7,312 | High — many SQL migrations across 5 repos |
| Quadim | 52,290 | Very high — h2 + postgres variants × 38 repos |
| Cantara | 8,004 | SQL migration cross-repo links confirmed (Whydah→ConfigService) |
| exoreaction | 847 | Low — mostly library repos with few SQL |

**Quadim's high count explained:** Two DB variants (`h2` for testing, `postgres` for production)
× many SQL files × many Java consumers = legitimate multi-variant cross-linking.
After `target/` fix, all 52K links point to `src/main/resources/` only (correct).

---

## Extraction Performance

| Workspace | Repos | Files | Time | Files/sec |
|-----------|-------|-------|------|-----------|
| Synthesis | 1 | 501 | 33s | 15 |
| Elprint | 5 | 1,194 | 120s | 10 |
| exoreaction | 33 | 3,057 | 205s | 15 |
| Cantara | 54 | 4,273 | 289s | 15 |
| Quadim | 38 | 2,771 | 252s | 11 |

Consistent ~10-15 files/second throughput. Elprint and Quadim are slower (10-11 files/sec)
due to higher cross-format link density requiring more SQL scanning work.

**Scaling projection:**
- 100 repos ≈ 10,000 files ≈ ~650-1000s (10-17 min) for a large multi-repo org
- Incremental extraction (after first full run) significantly faster

---

## Key Takeaways

1. **CKG works at portfolio scale** — 54 repos, 4,273 files, 289s. Cross-repo deps visible.
2. **Config cycles are universal** — design guideline needed: never put service imports in config classes.
3. **God service is universal** — design guideline needed: bounded-context service decomposition.
4. **Format parsing coupling is inherent** — not all C001 signals are debt; MIF complexity is real.
5. **Whydah debt is the portfolio's biggest risk** — fixing it would improve 3 workspaces.
6. **Synthesis as the design target** — 2 cycles, 0 hotspots, clean layers. This is what good looks like.

---

*Last updated: February 22, 2026*
