# CKG Dogfooding Findings — Elprint Workspace

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Workspace:** `/src/elprint`
**Analyst:** Thor Henning Hetland + Claude

Elprint Velocity — PCB component/BOM management platform for the electronics manufacturing industry.
5 repos extracted as a single workspace.

---

## Extraction Stats

```
Files processed:    1194
Dependencies found: 11723
Cross-format links: 7312
Packages found:     85
External deps:      9283
Elapsed:            120s
Module profiles:    85 (auto-computed)
```

---

## Project Structure

Two distinct module namespaces extracted together:

| Namespace | Module | Description |
|-----------|--------|-------------|
| `com.exoreaction.elprint.velocity.*` | Main app | Core Elprint Velocity backend |
| `com.elprint.velocity.*` | PCBA Agent | PCB assembly agent module |
| `com.componentdb.*` | Component DB | Component database extraction (orphaned) |

---

## Architecture Layer Map

```
Layer 4 — Entry/CLI (instability 1.00)
  com/elprint/velocity                        fan-in:  0  fan-out:  4  instability: 1.00
  com/elprint/velocity/pcba/agent             fan-in:  0  fan-out:  4  instability: 1.00
  com/exoreaction/elprint/velocity/api        fan-in:  0  fan-out: 10  instability: 1.00
  com/exoreaction/elprint/velocity/bom        fan-in:  0  fan-out:  1  instability: 1.00
  com/exoreaction/elprint/velocity/e2e        fan-in:  0  fan-out:  3  instability: 1.00
  com/exoreaction/elprint/velocity/filter     fan-in:  0  fan-out:  2  instability: 1.00
  com/exoreaction/elprint/velocity/integration fan-in: 0  fan-out:  8  instability: 1.00
  com/exoreaction/elprint/velocity/resource   fan-in:  3  fan-out: 26  instability: 0.90

Layer 3 — Application (instability 0.51-0.75)
  com/exoreaction/elprint/velocity/service    fan-in: 20  fan-out: 31  instability: 0.61  ← god hub
  com/exoreaction/elprint/velocity/security   fan-in:  5  fan-out:  7  instability: 0.58
  com/exoreaction/elprint/velocity/executor   fan-in:  2  fan-out:  4  instability: 0.67
  com/exoreaction/elprint/velocity/security/cacheclient fan-in: 6 fan-out: 10  instability: 0.63

Layer 2 — Core Services (instability 0.26-0.50)
  com/exoreaction/elprint/velocity/domain/*   fan-in: 5-17 fan-out: 0-6 instability: 0.10-0.55 ✓
  com/exoreaction/elprint/velocity/cache      fan-in:  ?   fan-out:  ?   instability: ?
  com/exoreaction/elprint/velocity/config     fan-in:  ?   fan-out:  ?   instability: ?

Layer 1 — Foundation (instability 0.00-0.25)
  com/exoreaction/elprint/velocity/entity     fan-in: 19  fan-out:  1  instability: 0.05 ✓ (main app)
  com/exoreaction/elprint/velocity/repository fan-in: 14  fan-out:  3  instability: 0.18 ✓
  com/exoreaction/elprint/velocity/util       fan-in:  ?   fan-out:  ?   instability: ?
  com/exoreaction/elprint/velocity/domain/componentservice fan-in: 17 fan-out: 3 instability: 0.15 ✓
```

---

## Circular Dependencies (22 cycles)

### Pattern: `service` is the entanglement hub

`service` (127 files) is at the center of 7 out of 22 cycles:

```
service ↔ repository     (1 + 92 edges)   ← heaviest
service ↔ exception      (40 + 1 edges)
service ↔ cache          (19 + 1 edges)
service ↔ security       (19 + 2 edges)
service ↔ security.config (14 + 4 edges)
service ↔ cacheclient    (39 + 15 edges)
service ↔ executor       (6 + 4 edges)
```

**Root cause:** `service` is a monolith orchestrator — it imports from nearly every other package, and many of those packages import service types back. Classic "god service" in a Spring Boot app that grew without layering discipline.

**Fix direction:** Split `service` by domain feature (component, inventory, project, reporting, BOM, user). Each feature service should only know its own domain sub-services.

### Other Notable Cycles

| Cycle | Edges | Assessment |
|-------|-------|------------|
| `entity ↔ repository` | 2 + 111 | **Critical** — JPA layer deeply entangled with entity model |
| `entity ↔ util` | 27 + 2 | Entity utility coupling — util imports entity types |
| `domain ↔ entity` | 1 + 71 | Domain model importing entity objects (backwards) |
| `component/factory ↔ provider` | 5 + 5 | Factory/provider mutual coupling — extract interface |
| `componentservice ↔ inventoryservice` | 1 + 8 | Cross-domain service coupling |
| `projectservice ↔ reportingservice` | 1 + 8 | Cross-domain service coupling |
| `excelbomparser ↔ service` | 2 + 5 | Parser pulling in service (wrong direction) |
| `executor ↔ macaos` | 4 + 1 | External integration coupling |

---

## Health Signals (56 issues)

### HIGH: 22 C001 Circular Dependencies
All listed above. Dominated by `service` hub cycles.

### HIGH: 3 C013 Unstable Core
Packages that should be stable foundations but are highly unstable:

| Package | Instability | Problem |
|---------|------------|---------|
| `domain/converter` | 0.88 | Converter should be stable utility, not depend on 7 others |
| `domain/reportingservice` | 0.55 | Core reporting service has too many outgoing deps |
| `domain/stats` | 0.67 | Stats domain reaching upward into application layer |

### MEDIUM: 10 C012 God Packages

| Package | Files | Assessment |
|---------|-------|------------|
| `service` | 127 | **Critical** — split immediately |
| `entity` | 96 | Large but coherent — JPA entity model |
| `repository` | 86 | Large but coherent — Spring Data repositories |
| `util` | 52 | Utility sprawl — consider sub-packages |
| `requestbody` | 55 | API request bodies — OK for REST layer |
| `domain` | 55 | Domain parent too large — sub-packages exist but parent bloated |
| `resource` | 63 | JAX-RS resources — entry point, expected |
| `domain/requestbody` | 33 | API request bodies |
| `config` | 33 | Configuration classes |
| `controllers` | 34 | PCBA Agent controllers |

### MEDIUM: 9 C010 High Fan-in Without Tests

These are domain services that are widely used but have no corresponding test package:

| Package | Fan-in | Risk |
|---------|--------|------|
| `domain/componentservice` | 17 | **Highest** — most-used domain service |
| `domain/projectservice` | 10 | High |
| `domain/aiassistanceservice` | 9 | High |
| `domain/platformservice` | 9 | High |
| `repository` | 14 | High |
| `exception` | 8 | Medium |
| `domain/inventoryservice` | 8 | Medium |
| `domain/salescoreservice` | 7 | Medium |
| `service/cacheclient` | 6 | Medium |

**Note:** Unlike Synthesis's false positives, these C010 signals are likely **real** — Elprint is a production Spring Boot app where test coverage of domain services would be in integration tests, not mirrored package directories.

### LOW: 8 C014 Orphan Packages (Fan-in=0, Fan-out=0)

| Package | Assessment |
|---------|------------|
| `componentlibrag` | RAG (Retrieval-Augmented Generation) for component library — **new AI experiment, not yet wired** |
| `componentlibrag/util` | Supporting utilities for RAG experiment |
| `llm` | LLM integration layer — **new AI experiment, not yet wired** |
| `component` | Isolated component module — unclear purpose |
| `componentlibrag/util` | RAG utilities |
| `com/componentdb/extraction/agents` | Component DB extraction agents |
| `com/componentdb/extraction/config` | Component DB config |
| `com/componentdb/extraction/tools` | Component DB tools |

**Interesting:** `componentlibrag` and `llm` reveal in-progress AI integration work — RAG for component search, LLM queries. `aiassistanceservice` (fan-in: 9, stable) is the mature AI feature; these are the experiments.

### LOW: 4 C021 Documentation Gaps

`entity` (fan-in: 19), `repository` (fan-in: 14), `exception` (fan-in: 8), `pcba/agent/health` (fan-in: 6) — all widely used, none documented.

---

## AI Features — Two Generations

One of the most interesting findings: Elprint is in the middle of an AI evolution.

```
Generation 1 (mature, stable):
  domain/aiassistanceservice   fan-in: 9, instability: 0.10 ✓
  Purpose: Core domain model
  → Wired into the main application, 7 files, stable
  → This is a functioning AI assistance service

Generation 2 (experimental, orphaned, confidence: 0.30):
  componentlibrag              fan-in: 0, fan-out: 0, 2 files
  componentlibrag/util         fan-in: 0, fan-out: 0, 6 files
  llm                          fan-in: 0, fan-out: 0, ? files
  → These are RAG/LLM experiments not yet connected to the app
  → The 0.30 confidence score correctly identifies them as disconnected
```

---

## Instability Profile

| Package | Fan-in | Fan-out | Instability | Layer |
|---------|--------|---------|-------------|-------|
| `domain/componentservice` | 17 | 3 | 0.15 | Foundation |
| `entity` | 19 | 1 | 0.05 | Foundation |
| `repository` | 14 | 3 | 0.18 | Foundation |
| `domain/aiassistanceservice` | 9 | 1 | 0.10 | Foundation |
| `service` | 20 | 31 | 0.61 | Application ⚠ |
| `resource` | 3 | 26 | 0.90 | Entry (expected) |
| `integration` | 0 | 8 | 1.00 | Entry |
| `api` | 0 | 10 | 1.00 | Entry |

The foundation is solid (entity, repository, domain services all stable). The application layer is where the architectural debt lives.

---

## Cross-Format Links (7,312)

High cross-format count (7,312 for 5 repos) suggests significant SQL-to-Java linking. Elprint likely has many Flyway migrations and SQL queries mapped to Java service classes. No target/ double-count issues (already fixed in v1.13.1).

---

## Architecture Quality: ★★★☆☆

**Strengths:**
- Foundation layer is clean and stable (entity, repository, domain services all low instability)
- Domain model is properly decomposed into feature sub-services (componentservice, inventoryservice, projectservice, etc.)
- E2E test infrastructure exists (`e2e` + `e2e/scenarios` packages)
- Active AI feature development (two generations visible in the graph)

**Weaknesses:**
- `service` god class (127 files, hub of 7 circular deps) needs urgent decomposition
- `entity ↔ repository` mutual coupling (111 edges) — classic anemic domain model problem
- No test coverage for any domain service packages (C010 × 9)
- `componentlibrag` and `llm` experiments are orphaned — not yet integrated

---

## Recommended Follow-up

| Priority | Action | Effort |
|----------|--------|--------|
| HIGH | Split `service` (127 files) by domain feature | Large |
| HIGH | Break `entity ↔ repository` cycle (111 edges) — repository should own entity, not vice versa | Medium |
| HIGH | Break `domain ↔ entity` cycle — domain should not import entity objects | Medium |
| MEDIUM | Move `excelbomparser` parsing to not depend on service | Small |
| MEDIUM | Wire `componentlibrag` + `llm` into main app, or delete if stale | Small |
| MEDIUM | Add test packages for top 3 domain services (componentservice, projectservice, aiassistanceservice) | Medium |
| LOW | Extract `domain/converter` outgoing deps to reduce instability | Small |

---

*Last updated: February 22, 2026*
