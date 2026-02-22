# CKG Dogfooding Findings — Quadim Workspace

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Workspace:** `/src/quadim`
**Analyst:** Thor Henning Hetland + Claude

Quadim — production SaaS platform for skills management, HR analytics, and competency mapping.
38 repos (some non-Java excluded by CKG-4 non-Java skip fix).

---

## ⚠ Reliability Warning (Issue #232 — Repo Isolation Missing)

**52% of detected Quadim packages span multiple repos — this analysis is provisional.**

Quadim has 38 microservices all using the `ai.quadim.api.*` namespace. The CKG merges them
into shared package nodes. Key consequences:

- `ai.quadim.api.service` (86 files, fan-out 46) = ~25 repos × ~3 files each — not a god package
- **87% of the 47 detected cycles are likely false positives** (cross-repo merging artifacts)
- `api/controller` god-controller (fan-out 40) = collective deps of ~20 separate controllers
- Instability scores, hotspots, and health signals for `ai.quadim.api.*` packages are unreliable

The DTO foundation finding (overlord fan-in: 43, instability: 0.04) remains likely valid — DTOs
are truly foundational across all repos. But all cycle and hotspot findings should be reverified
after #232 is fixed.

---

## Extraction Stats (post-fix, PR #222 + #228)

```
Files processed:    2771
Dependencies found: 27908
Cross-format links: 52290   (was 100054 before target/ fix — 48% reduction)
Packages found:     222
External deps:      20612
Elapsed:            252s    (was 985s before target/ fix — 4x speedup)
Module profiles:    222 (auto-computed, no --refresh needed)
```

**Note on cross-format:** 52,290 links is still high. Root cause: each SQL migration has
both `h2` and `postgres` variants under `src/main/resources/` — each generates cross-format
links separately. This is accurate (both DB variants exist), not a bug.

---

## Architecture: The DTO Foundation Pattern

Quadim follows a clear Spring Boot microservice architecture with a dominant DTO layer:

```
Layer 1 — Foundation (stable DTOs)
  ai/quadim/api/dto/overlord        fan-in: 43  fan-out:  2  instability: 0.04  ✓ ← most stable
  ai/quadim/api/base                fan-in: 23  fan-out:  2  instability: 0.08  ✓
  ai/quadim/api/dto/...             fan-in: 6-16 fan-out: 0-2 instability: 0.04-0.25 ✓
  (CV format DTOs: jsonresume, freshresume, linkedinjsonexport)

Layer 2 — Core Services
  ai/quadim/api/modules/*/service   fan-in: 5-9  fan-out: 15-25 instability: 0.60-0.74
  ai/quadim/api/security/whydah     fan-in: 4   fan-out: 10  instability: 0.71  ⚠ hotspot

Layer 3 — Application
  ai/quadim/api/config              fan-in: 3-8  fan-out: 10-20  ← circular dep hub
  ai/quadim/api/service             fan-in: ?    fan-out: ?

Layer 4 — Entry
  ai/quadim/api/controller          fan-in: 3   fan-out: 40  instability: 0.93  ⚠ hotspot god-controller
```

**Key insight:** The DTO layer (`dto/overlord` at fan-in 43) is the true foundation of Quadim —
the skills/competency data model is what everything else depends on. Clean and stable.

**CV format DTOs are prominent:** `jsonresume` (fan-in 16), `freshresume` (fan-in 6),
`linkedinjsonexport` (fan-in 8) — Quadim's CV export features are widely consumed across
the service layer. The CV integration is a first-class architectural concern.

---

## Circular Dependencies (47 cycles)

### Config Package: The Circular Dep Hub

`api.config` is involved in 5 of the 47 cycles — the universal "config as garbage collector"
anti-pattern:

```
api.config ↔ api.service            (5 + 32 edges)  ← heaviest
api.config ↔ api.qplatform.dto      (7 + 9 edges)
api.config ↔ api.converter          (? + ? edges)
api.config ↔ api.resource.util      (? + ? edges)
api.config ↔ api.util               (? + ? edges)
```

Same pattern as Synthesis (`config ↔ core`) and Elprint (`service` hub) — but in Quadim
it's `config` that accumulates dependencies in all directions.

**Root cause:** Spring `@Configuration` classes often need access to service beans to wire
up Spring components, and service beans import config types for their behavior. The
dependency flows both ways, creating cycles.

### Other Notable Cycles

```
ai.quadim.api ↔ api.service         (5 + 1 edges)
ai.quadim.api.base ↔ api.service    (1 + 4 edges)
```

The top-level `api` and `api.base` packages have cycles with service — the root package
is not truly a root in the architectural sense; it participates in the service layer.

---

## Health Signals (49 issues)

### HIGH: 22+ C001 Circular Dependencies
Dominated by `api.config` hub (5 cycles) and service-layer entanglement.

### HIGH: Hotspots (7 found)

| Package | Fan-in | Fan-out | Instability | Issue |
|---------|--------|---------|-------------|-------|
| `api/controller` | 3 | 40 | 0.93 | **God controller** — 40 outgoing deps! |
| `modules/overlord/service` | 9 | 25 | 0.74 | Core business service, too many deps |
| `api/qplatform/bootstrap` | 5 | 17 | 0.77 | Bootstrap orchestrator |
| `api/security/whydah` | 4 | 10 | 0.71 | Auth integration hotspot |
| + 3 more |   |   |   |   |

**`api/controller`** with fan-out 40 is the most extreme entry point in all 5 codebases examined.
A single controller package depending on 40 other packages is a sign that the controller layer
is doing too much business logic (should delegate to services, not orchestrate directly).

### MEDIUM: C012 God Packages
Multiple packages over 30-file threshold — expected for a mature Spring Boot monolith.

---

## Notable Architectural Findings

### 1. Whydah SSO Visible Cross-Repo

`ai/quadim/api/security/whydah` (hotspot, fan-in: 4, fan-out: 10) — Quadim uses Cantara's
Whydah for authentication. This cross-org dependency is correctly captured in the graph.
Changes to Whydah SSO interfaces would affect Quadim's auth layer.

### 2. CV Format as First-Class Architecture

The CV export formats (`jsonresume`, `freshresume`, `linkedinjsonexport`) are prominently
visible in the dependency graph as widely-consumed DTO packages. This reflects Quadim's
core product value: CV/skills data in multiple formats.

```
ai/quadim/api/dto/jsonresume        fan-in: 16
ai/quadim/api/dto/linkedinjsonexport fan-in: 8
ai/quadim/api/dto/freshresume       fan-in: 6
```

### 3. Non-Java Repos Correctly Excluded

Before PR #228 fix, `Quadim-Ai-Nuxt` (Vue.js/Nuxt frontend) and CloudFormation YAML repos
were included in the Java CKG. After fix, only Java repos contribute.
`Quadim-Ai-Nuxt`'s `pages/` directory was appearing as a Java package.

### 4. DTO Layer is Architecturally Correct

Unlike Elprint (where entity↔repository cycle is problematic), Quadim's DTO layer is
clean: `dto/overlord` at instability 0.04 with fan-in 43 means everything depends on it
but it depends on almost nothing. Textbook stable foundation.

---

## Architecture Quality: ★★★★☆

**Strengths:**
- DTO foundation layer is clean and stable (overlord at fan-in 43, instability 0.04)
- CV format DTOs are properly positioned as stable foundations
- Non-Java repos correctly excluded from analysis
- Whydah dependency correctly visible as cross-repo link

**Weaknesses:**
- `api.config` hub: 5 circular dep cycles — the Spring config accumulation problem
- `api/controller` god-controller (fan-out: 40) — too much logic at the entry point
- 47 total cycles — significant Spring Boot growth debt
- 7 hotspots — several packages under simultaneous high coupling pressure

**Compared to Elprint:** Better foundation, worse config layer. Quadim's entity model
(DTO layer) is clean; Elprint's isn't. But Quadim's config hub is more problematic
than Elprint's service hub.

---

## Recommended Follow-up

| Priority | Action | Effort |
|----------|--------|--------|
| HIGH | Break `api.config ↔ api.service` (32 edges) — the dominant cycle | Large |
| HIGH | Decompose `api/controller` (fan-out: 40) into feature controllers | Medium |
| MEDIUM | Extract shared types from `api.config` cycles to a `model` package | Medium |
| MEDIUM | Stabilize `api/qplatform/bootstrap` — it shouldn't be a hotspot | Medium |
| LOW | Add package-info.java for high fan-in packages lacking docs | Small |

---

*Last updated: February 22, 2026*
