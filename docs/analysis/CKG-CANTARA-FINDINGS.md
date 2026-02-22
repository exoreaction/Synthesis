# CKG Dogfooding Findings — Cantara Workspace

**Date:** February 22, 2026
**Version:** v1.13.1-SNAPSHOT
**Workspace:** `/src/cantara`
**Analyst:** Thor Henning Hetland + Claude

Cantara — open-source multi-repo platform spanning IAM (Whydah), PCB tooling (lib-pcb cross-reference),
reactive services (xorcery), and developer infrastructure. 54 repos.

---

## ⚠ Reliability Warning (Issue #232 — Repo Isolation Missing)

**15% of Cantara packages span multiple repos — partially affects this analysis.**

Cantara's 54 repos use more distinct namespaces than Quadim (each Whydah component has its own
package prefix), so the impact is lower. However, shared `net.whydah.sso.*` and
`com.exoreaction.reactiveservices.*` namespaces are merged across repos:

- Whydah cycle counts may be inflated by cross-repo merging (auth/dao/utils in multiple repos)
- Fan-in counts for Spring `@Autowired` (272) are correct — it's an external dependency, unaffected
- `cantara-docsite` (single repo, distinct namespace) findings are fully reliable

The overall pattern (Whydah has significant debt, reactive services are clean) is likely still
correct directionally, but exact cycle counts and health signal numbers are provisional until
#232 is resolved.

---

## Extraction Stats (post-fix, PR #222 + #228)

```
Files processed:    4273
Dependencies found: 36964
Cross-format links: 8004    (was 15592 before target/ fix — 49% reduction)
Packages found:     746
External deps:      23919
Elapsed:            289s    (was 417s before target/ fix — 31% speedup)
Module profiles:    746 (auto-computed)
```

---

## Architecture Overview

Cantara is the largest workspace by package count (746) — a true multi-repo monorepo spanning
distinct technical domains. The dependency graph reveals a hub-and-spoke structure
around Whydah SSO.

```
Most stable (fan-in 40-272, low instability):
  org/springframework/beans/factory/annotation   fan-in: 272   ← Spring @Autowired (expected)
  net/whydah/sso/*                               fan-in: 40-70 ← Whydah core types
  com/exoreaction/reactiveservices/disruptor      fan-in: 19    instability: 0.00 ✓
  com/exoreaction/reactiveservices/jaxrs          fan-in: 23    instability: 0.12 ✓
```

**Spring `@Autowired` at fan-in 272** — the most-depended-on package in any workspace examined.
This is the Spring dependency injection annotation used across all 54 repos. Correct and expected.

**Whydah SSO packages (fan-in 40-70)** — Whydah is the authentication backbone of the entire
Cantara ecosystem. Nearly every service depends on Whydah SSO types for authentication.

---

## Circular Dependencies (128 cycles)

The largest cycle count across all workspaces, heavily concentrated in Whydah IAM packages.

### Whydah Identity Backend (~40 cycles, worst offender)

```
net/whydah/identity ↔ net/whydah/identity/dataimport                        (8 edges)
net/whydah/identity/application ↔ net/whydah/identity/dataimport            (32 + 32 edges) ← equal both ways
net/whydah/sso/session ↔ ...                                                 (4 C001 signals)
net/whydah/sts/application ↔ ...                                             (4 C001 signals)
net/whydah/sso/dao ↔ utils                                                   (10 + 10 edges)
net/whydah/sso/useradmin/consent ↔ traq                                      (3 + 26 edges)
net/whydah/sso/authentication/* ↔ dao/clients/crmcustomer                   (multiple)
```

**`whydah/identity/application ↔ dataimport` (32 + 32 edges)** — the heaviest bidirectional
cycle in Cantara. Application and data import are equally coupled to each other — neither is
stable. This is deep architectural debt in the Whydah identity service.

### cantara-docsite: Structurally Broken (9 cycles in one package)

```
no/cantara/docsite/cache ↔ client/domain/config/links (multiple pairs)
```

`cantara-docsite` has 9 circular dependency pairs within the `cache` package alone, plus:
- 11 C013 unstable-core signals
- 9 C010 missing-test signals

This package needs complete architectural redesign (cache, client, domain, config, links are
all tangled together with no clear layering).

### Xorcery Reactive Services (~15 cycles)

The Xorcery reactive services framework has coupling in its domain and event-handling layers,
but less severe than Whydah.

### Other Projects

The remaining ~65 cycles are distributed across smaller Cantara projects:
- PCB-related repos (lib-pcb fragments)
- Analytics services
- Configuration management tools

---

## Health Signals (163 issues)

The highest health signal count across all workspaces — expected for a 54-repo platform
with significant age and organic growth.

### HIGH: 128 C001 Circular Dependencies
Dominated by Whydah IAM packages.

### MEDIUM/HIGH: 37 Hotspots (C020)

The highest hotspot count of any workspace (Quadim: 7, exoreaction: 6, Synthesis: 0).
Top hotspots:

| Package | Fan-in | Fan-out | Instability | Component |
|---------|--------|---------|-------------|-----------|
| `net/whydah/service` | 10 | 27 | 0.73 | Whydah — service layer hub |
| `net/whydah/sts/user` | 6 | 31 | 0.84 | Whydah STS — user management |
| `net/whydah/identity/application` | 5 | 20 | 0.80 | Whydah — identity application |

All top hotspots are in Whydah packages. The Whydah IAM platform is the architectural
debt concentration point for the entire Cantara ecosystem.

### Cross-Format Links: Multi-Repo SQL Working

Cross-repo SQL links confirmed working:
```
Whydah-UserIdentityBackend/src/main/resources/db/migration/mariadb/V1_1_0__appModelJson.sql
→ ConfigService-Dashboard/Main.java (via table reference)
```

This cross-repo SQL-to-Java link is a key value-add of workspace-level extraction —
invisible when each repo is analyzed in isolation.

---

## Notable Findings

### 1. Whydah SSO is the Cantara Monolith

Despite being spread across many repos, Whydah SSO functions architecturally as a monolith:
- Most-depended-on packages in the workspace
- Highest hotspot concentration
- Most circular dependencies
- Oldest code, organically evolved

Whydah's debt is Cantara's debt — any changes in Whydah cascade to all 54 repos.

### 2. Spring `@Autowired` Universally Visible

`org.springframework.beans.factory.annotation` at fan-in 272 across 54 repos — every
Spring bean in every service uses `@Autowired`. After the FQN fix (PR #228), Spring
packages are correctly marked `is_external=1` and not confused with internal packages.

### 3. `cantara-docsite` as a Warning Sign

A documentation site that's structurally broken (9 circular deps in cache alone) and has
no tests suggests it was rapidly prototyped and never refactored. Low priority to fix,
but worth noting as a maintenance risk.

### 4. Reactive Services Layer is Relatively Clean

`com/exoreaction/reactiveservices/disruptor` (fan-in: 19, instability: 0.00) and
`com/exoreaction/reactiveservices/jaxrs` (fan-in: 23, instability: 0.12) are among
the cleanest high-fan-in packages in the workspace. The event bus and JAX-RS layer
were designed with proper stability in mind.

### 5. Cross-Codebase Whydah Consistency

Whydah cycles appear both here (Cantara) and in the exoreaction workspace (where
`1881-SSOLoginWebApp` uses it). The same `dao ↔ utils`, `clients ↔ dao`,
`authentication ↔ dao` patterns appear in both — confirming these are real Whydah
architectural issues, not workspace extraction artifacts.

---

## Architecture Quality: ★★☆☆☆

**Strengths:**
- Reactive services layer (disruptor, jaxrs) is clean and stable
- Spring `@Autowired` correctly identified as high fan-in (external) after FQN fix
- Cross-repo SQL links work correctly across Whydah repos
- Large project diversity visible in one graph

**Weaknesses:**
- 128 cycles — the highest of any workspace
- 37 hotspots — the highest of any workspace
- Whydah IAM is structurally problematic (40+ cycles, all top hotspots)
- `cantara-docsite` structurally broken (9 cycles, no tests)
- Organic growth over years without architectural refactoring visible

**Note:** The low quality score reflects Whydah's age and scale more than Cantara's own code.
The reactive services and newer Cantara components score higher individually.

---

## Recommended Follow-up

| Priority | Action | Effort |
|----------|--------|--------|
| HIGH | Decompose `whydah/identity/application ↔ dataimport` (32+32 edges) | Large |
| HIGH | Refactor `cantara-docsite` cache/client/domain/config — complete redesign | Large |
| HIGH | Break `net/whydah/service` god service (fan-out: 27, 10 dependents) | Large |
| MEDIUM | Stabilize `whydah/sts/user` — reduce fan-out from 31 | Medium |
| MEDIUM | Add tests for `cantara-docsite` (C010 × 9 missing test packages) | Medium |
| LOW | Document reactive services layer (disruptor, jaxrs) — they're the best part | Small |

---

*Last updated: February 22, 2026*
