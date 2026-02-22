# CKG Dogfooding Findings — eXOReaction Workspace

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Workspace:** `/src/exoreaction`
**Analyst:** Thor Henning Hetland + Claude

eXOReaction portfolio workspace — 33 repos spanning multiple products and client projects,
all extracted together as a single cross-repo dependency graph.

---

## Extraction Stats

```
Files processed:    3057
Dependencies found: 24624
Cross-format links: 847
Packages found:     386
External deps:      18773
Elapsed:            205s
Module profiles:    386 (auto-computed)
```

**Note:** Low cross-format count (847 for 33 repos) — most repos here are libraries and products
with fewer SQL migrations than a Spring Boot monolith like Elprint.

---

## Project Map (by package prefix)

| Package Prefix | Project | Type |
|---------------|---------|------|
| `no.cantara.pcb.lib.*` | **lib-pcb** | PCB format library (parsers, validators, exporters) |
| `no.cantara.pcb.app.*` | **lib-pcb-app** | PCB application service layer |
| `dev.xorcery.aurora.*` | **Xorcery Aurora** | Production temporal analytics engine |
| `com.aurorapoc.*` | **Aurora PoC** | PoC/research data generator |
| `com.exoreaction.catalystone.*` | **CatalystOne Analytics** | Neo4j + GraphQL analytics platform |
| `io.exoreaction.synthesis.*` | **Synthesis** | Knowledge infrastructure tool |
| `net.whydah.sso.*` | **Whydah SSO** | Single sign-on integration layer |
| `no.pasientsky.*` | **Pasientsky** | Healthcare claims processing + OCR |
| `com.exoreaction.notification.*` | **Notification** | Notification service |
| `dev.xorcery.aaa.*` | **Xorcery AAA** | Authentication/authorization/accounting |

---

## Circular Dependencies (83 cycles)

### lib-pcb: Format Parser Coupling (expected, ~18 cycles)

The PCB format parsers have inherent model↔parser coupling because format types and
parsers evolve together. Most cycles here are structural, not accidental:

```
mif/parser ↔ mif/features     (2 + 65 edges)  ← heaviest cycle in the workspace
mif/parser ↔ mif/shapes       (3 + 54 edges)
mif/writer ↔ mif/features     (2 + 31 edges)
mif/writer ↔ mif/shapes       (2 + 27 edges)
mif/parser ↔ mif/writer       (1 + 4 edges)
gerber ↔ gerber/parser        (6 + 1 edges)
gerber/model ↔ gerber/parser  (5 + 2 edges)
validator ↔ validator/validators (3 + 33 edges)  ← heaviest validator cycle
validator ↔ validator/reports  (3 + 14 edges)
excellon ↔ excellon/converter  (1 + 1 edges)
excellon ↔ excellon/util       (3 + 2 edges)
kicad ↔ kicad/converter        (1 + 9 edges)
odbpp ↔ odbpp/parser           (1 + 8 edges)
export/filter ↔ export/pnp    (1 + 6 edges)
ipc2581/importer ↔ converters  (1 + 13 edges)
pkg ↔ pkg/gbrjob               (1 + 7 edges)
pricing ↔ pricing/model        (1 + 3 edges)
```

**MIF format is the most complex** — its parser/features/shapes triangle has the heaviest coupling
in the entire workspace. MIF is a complex binary PCB format; this coupling likely reflects
real format complexity rather than design debt.

**`validator ↔ validators`** (33 edges) — the base `Validator` interface and its 28 implementations
are tightly coupled. The implementations call back into the validator framework for cross-validation.

### Xorcery Aurora: Change Model Coupling (~12 cycles)

```
aurora/changes ↔ aurora/changes/element    (3 + 4 edges)
aurora/changes ↔ aurora/changes/fieldvalue (2 + 2 edges)
aurora/changes ↔ aurora/changes/mapper     (2 + 2 edges)
aurora/changes ↔ aurora/changes/model      (6 + 3 edges)
aurora/changes ↔ aurora/changes/query      (1 + 2 edges)
aurora/changes/element ↔ changes/fieldvalue (10 + 1 edges)
aurora/graphql/api ↔ aurora/graphql/api     (SELF-REFERENCE: 1 + 1 edges)  ← unusual
aurora/graphql/api/changes ↔ graphql/api/model (1 + 8 edges)
aurora/graphql/api/directives ↔ helpers    (7 + 1 edges)
aurora/graphql/api/fetcher ↔ function      (1 + 3 edges)
aurora/graphql/api/fetcher ↔ acl           (1 + 3 edges)
aurora/graphql/datafetcher/fetchers ↔ input (1 + 1 edges)
```

**`aurora/changes` is a hotspot** (fan-in: 6, fan-out: 19, instability: 0.76) — the change event
model is entangled with model, element, fieldvalue, mapper, and query sub-packages simultaneously.
This is the most complex part of Aurora.

**Self-referential import in `aurora/graphql/api`** — a package importing itself. This may be
a cross-sub-package import (api.changes imports api.model which imports api.changes) resolved
at the parent level. Worth investigating.

### CatalystOne Analytics: GraphQL Factory Coupling (~10 cycles)

```
catalystone/graphql ↔ graphql/factory          (1 + 9 edges)
graphql/factory ↔ graphql/factory/query        (2 + 3 edges)
graphql/factory ↔ graphql/factory/where        (1 + 3 edges)
graphql/factory ↔ neo4j/extensions             (1 + 2 edges)
graphql/factory/query ↔ factory/where          (6 + 1 edges)
neo4j/acl ↔ neo4j/model                       (4 + 1 edges)
neo4j/model ↔ neo4j/report/change/model        (1 + 9 edges)
neo4j/model ↔ projection/neo4j/projection      (2 + 4 edges)
neo4j/report/change/model ↔ change/query       (3 + 25 edges)
neo4j/report/change/model ↔ change/result      (6 + 10 edges)
```

CatalystOne uses Neo4j + GraphQL for analytics. The factory/query/where pattern
(building dynamic Cypher queries) is inherently complex — the where-clause builders
need to know about factory types and vice versa.

### Whydah SSO: Auth Layer Coupling (~12 cycles)

```
sso ↔ sso/authentication
sso ↔ sso/utils
authentication ↔ dao
authentication/iamproviders ↔ dao
facebook ↔ clients
netiq ↔ clients
clients ↔ dao
clients ↔ crmcustomer
whydah ↔ useradmin
whydah ↔ whydah/utils
whydah/clients ↔ dao
dao ↔ crmcustomer  (2 separate cycles: 1+4 edges and 2+5 edges)
dao ↔ utils        (10 + 10 edges)
useradmin/consent ↔ traq  (3 + 26 edges)
```

Whydah SSO has similar coupling to what's seen in the Cantara workspace — auth/dao/utils
are deeply entangled. The `consent ↔ traq` cycle (26 edges) is the heaviest individual
Whydah cycle.

### Pasientsky: Healthcare Claims (~5 cycles)

```
flokkun/domain/claimresponse ↔ dataimport
flokkun/domain/predator/inndata ↔ dataimport
flokkun/domain/rules ↔ dataimport
flokkun/service ↔ health
flokkun/service ↔ simulator
no/pasientsky/ocr ↔ ocr/workers
```

`flokkun` (Norwegian: "the flock") — healthcare claims processing. `dataimport` is at the
center of 3 domain cycles (claimresponse, inndata, rules all importing it back). This is
a common pattern when an import job needs to create domain objects and those objects
know about the import context.

### Synthesis: Own Cycles (2, visible here too)

```
synthesis/cli ↔ synthesis/integration  (4 + 4 edges)
synthesis/config ↔ synthesis/core      (3 + 10 edges)
```

Same 2 cycles as the Synthesis-on-itself extraction — consistent cross-workspace detection ✅.

### Aurora PoC: Calculator/Config Coupling (1 cycle)

```
aurorapoc/datagenerator/calculators ↔ config  (36 + 3 edges)
```

The heaviest single cycle in the workspace by edge count (36+3=39). The PoC data generator's
calculators import 36 config types, and config imports 3 calculator types back.
PoC code — expected to be messy.

---

## Health Signals (88 issues)

### HIGH: 83 C001 Circular Dependencies
All listed above. lib-pcb contributes the most (~18), Whydah SSO (~14), Aurora (~12), CatalystOne (~10), Pasientsky (~5), Synthesis (2), Aurora PoC (1), lib-pcb-app (1).

### HIGH: 4 C013 Unstable Core

| Package | Instability | Project |
|---------|------------|---------|
| `catalystone/dbtoevents/model` | 1.00 | CatalystOne — model depends on everything |
| `aurora/graphql/datafetcher/model` | 0.92 | Aurora — data fetcher model too unstable |
| `aurora/graphql/api/typedefinition/model/graphql` | 0.67 | Aurora — type definition model |
| `flokkun/service/domain` | 0.89 | Pasientsky — service-domain coupling |

### HIGH: 1 C020 Hotspot

| Package | Fan-in | Fan-out | Instability | Project |
|---------|--------|---------|-------------|---------|
| `no/cantara/pcb/app/service` | 5 | 33 | 0.87 | lib-pcb-app — **god service** |

lib-pcb-app's service layer (fan-out: 33!) orchestrates all PCB operations — parsing,
validating, exporting, pricing, BOM generation. Same god-service pattern as Elprint.

---

## Hotspots (6 found)

| Package | Fan-in | Fan-out | Instability | Project |
|---------|--------|---------|-------------|---------|
| `dev/xorcery/aurora/changes` | 6 | 19 | 0.76 | Aurora — change model hub |
| `no/cantara/pcb/app/service` | 5 | 33 | 0.87 | lib-pcb-app — god service |
| `no/pasientsky/flokkun/service` | 4 | 11 | 0.73 | Pasientsky — god service |
| `dev/xorcery/aurora/changes/element` | 3 | 8 | 0.73 | Aurora — element model |
| `net/whydah/sso/authentication/iamproviders/azuread` | 3 | 8 | 0.73 | Whydah — Azure AD integration |
| `net/whydah/sso/useradmin` | 3 | 9 | 0.75 | Whydah — user admin layer |

**Recurring pattern:** `service` packages in application-layer repos are consistently hotspots
across all eXOReaction codebases (lib-pcb-app, Elprint, Pasientsky).

---

## Cross-Repo Dependency Visibility

One of the most valuable features of workspace-level extraction — cross-repo dependencies
become visible. Confirmed cross-repo links:

- **Whydah SSO → CatalystOne:** Whydah authentication packages imported by CatalystOne analytics
- **lib-pcb → lib-pcb-app:** The library cleanly depended on by the app layer
- **Aurora PoC → Aurora core:** PoC imports from production Aurora library
- **Synthesis → Whydah:** Synthesis uses Whydah for auth in the 1881 integration

---

## Per-Project Architecture Quality

| Project | Cycles | Hotspots | Quality | Assessment |
|---------|--------|----------|---------|------------|
| Synthesis | 2 | 0 | ★★★★★ | Purpose-built, cleanest |
| lib-pcb | ~18 | 0 | ★★★★☆ | Format coupling expected for parser library |
| Aurora (production) | ~12 | 2 | ★★★☆☆ | GraphQL/change model coupling |
| CatalystOne | ~10 | 0 | ★★★☆☆ | Neo4j factory pattern coupling |
| Whydah SSO | ~14 | 2 | ★★☆☆☆ | Auth/dao/utils entanglement (3rd party) |
| lib-pcb-app | ~3 | 1 | ★★★☆☆ | God service, manageable |
| Pasientsky | ~5 | 1 | ★★★☆☆ | Small project, expected for claims domain |
| Aurora PoC | 1 | 0 | ★★☆☆☆ | PoC/research code, expected |

---

## Notable Findings

### 1. MIF Format is the Most Complex in lib-pcb

`mif/parser ↔ features` (65 edges) and `mif/parser ↔ shapes` (54 edges) — MIF (Manufacturing
Information Format) is the most complex format handled by lib-pcb. The parser, feature types,
and shape types are deeply co-evolved. This isn't a bug in the code — MIF is a complex format.

### 2. Universal God-Service Pattern

Three separate `service` packages appear as hotspots across different projects:
- `no/cantara/pcb/app/service` — fan-out: 33
- `no/pasientsky/flokkun/service` — fan-out: 11
- (Elprint's `service` — fan-out: 31, from separate workspace)

This is a cross-codebase architectural anti-pattern in eXOReaction's Java projects.
Consider a "service orchestration" architectural guideline for new projects.

### 3. Pasientsky — Previously Undocumented

The `no.pasientsky.*` packages reveal a healthcare project (`flokkun` = claims processing,
`ocr` = document recognition) not previously visible from docs. Small but non-trivial
(has god service, domain coupling, OCR module).

### 4. Aurora PoC vs Aurora Production Quality Gap

`aurorapoc` (PoC) has the heaviest single cycle (36 edges) and no architectural discipline.
`dev.xorcery.aurora` (production) has 12 cycles but organized structure. The PoC is not
representative of the production Aurora quality.

### 5. Xorcery AAA in the Graph

`dev.xorcery.aaa ↔ aaa/startup` (2 + 1 edges) — the AAA product is present but small
(only 1 cycle found). This suggests it's architecturally lean, possibly in early stages
or mostly library-composed.

---

## Architecture Quality: ★★★☆☆ (portfolio average)

This is a portfolio-level extraction across 10 distinct projects. The quality varies:
- **Top tier:** Synthesis (★★★★★), lib-pcb (★★★★☆)
- **Mid tier:** Aurora production, CatalystOne, lib-pcb-app, Pasientsky (★★★☆☆)
- **Lower tier:** Whydah SSO (★★☆☆☆), Aurora PoC (★★☆☆☆)

The portfolio-level view is most valuable for cross-repo dependency tracking, not
aggregate quality scoring.

---

## Recommended Follow-up

| Priority | Action | Project | Effort |
|----------|--------|---------|--------|
| HIGH | Refactor `aurora/changes` — split element/fieldvalue/model into stable sub-layer | Aurora | Large |
| HIGH | Break `lib-pcb-app/service` god-service (fan-out: 33) | lib-pcb-app | Large |
| MEDIUM | Investigate `aurora/graphql/api` self-referential import | Aurora | Small |
| MEDIUM | Break `neo4j/report/change/model ↔ query` (25 edges) | CatalystOne | Medium |
| MEDIUM | Break `mif/parser ↔ features` (65 edges) — consider visitor pattern | lib-pcb | Large |
| LOW | Move Aurora PoC code to `experimental/` branch or archive | Aurora PoC | Small |
| LOW | Document Pasientsky project in knowledge base | Pasientsky | Small |

---

*Last updated: February 22, 2026*
