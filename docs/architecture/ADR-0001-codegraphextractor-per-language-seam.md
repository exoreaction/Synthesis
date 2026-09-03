# ADR-0001: Per-language extraction seam for CodeGraphExtractor

**Date:** 2026-07-20
**Status:** Accepted — 4 sub-decisions resolved (design grill 2026-07-20); Q5/Q6/test-coupling/gaps resolved by maintainer review ([PR #457](https://github.com/exoreaction/Synthesis/pull/457), 2026-07-21)
**Issue:** [#428](https://github.com/exoreaction/Synthesis/issues/428)

## Context

`CodeGraphExtractor.java` inlines **three** languages of regex extraction — Java, TypeScript (#323), Kotlin (#406) — at ~1,150 lines. A fourth (Go, the concrete motivator) makes the single file untenable. #428 = a **design task** (define per-language contract + orchestrator boundary), not a mechanical refactor. "No behavior change / tests unmodified" is a *constraint*, not proof it's mechanical.

Inventory below is the source of truth — do not re-derive.

---

## Inventory (verified 2026-07-20, repo @ `main` `b172864`)

### A. Producers — write extracted code data

Center: `graph/CodeGraphExtractor.java:32`
- `extractAndPersist(Path, Connection)` — full scan+persist — `:142`
- `incrementalUpdate(Path, Connection, Set<Path>)` — `:249`. **NOT git-aware**: trusts the caller-supplied `changedFiles` set. Callers build it differently: CLI full `Files.walk` (`cli/CodeGraphCommand.java:388`, Java+Kotlin only — TS dropped); Maintain content-hash diff (`MaintainOrchestrator.java:1015`, `added()`/`modified()` only — `deleted()` never wired).
- Per-language inline: Java `extractStructuralDeps` `:628`; TS `extractTypeScript` `:787`/`extractTypeScriptFile` `:809`; Kotlin `extractKotlinImports` `:954`/`extractKotlinFiles` `:1109`/`extractKotlinFile` `:1137`; cross-format → `CrossFormatLinker.java:20` (`findSqlToJavaLinks` `:56`, `findYamlToJavaLinks` `:113`)
- Filters: `isBuildArtifact` `:597`, `isArchiveDirectory` `:618`
- In-memory maps (extractor-private, no external reader): `classToFile` (Java+Kotlin FQN, `:153`), `simpleNameIndex` (`:511`), `tsPathIndex` (TS, separate), `packageFunctionFiles` (Kotlin)

**Writers/DAO** `graph/CodeGraphRepository.java`: `upsertDependency` `:62`, `batchInsertCrossFormatLinks` `:231`, `deleteDependenciesForFile` `:201` (deletes `WHERE source_file` only), `upsertQualityGap` `:367`
**Derived (post-extraction stage):** `ModuleProfileComputer.java:29`, `QualityGapDetector.java:28`, `CompletenessScorer`

**CLI triggers:** `code-graph extract` (`--incremental`/`--dry-run`/`--stats`) `cli/CodeGraphCommand.java:230`; `describe/health/gaps --refresh` `:431`/`:641`/`:781`; `maintain` Phase 10 `MaintainOrchestrator.java:980`. NOT: `code-graph security --refresh` (`:967`, SecurityAnalyzer only), `relate --refresh`/`impact` (live `RelationService`, non-persisting).
**MCP trigger:** `code-graph` tool → `mcp/SynthesisToolHandler.java:2411` (shells to CLI; free-text `flags` reach persisting `--refresh` paths).

### B. Consumers — read extracted code data (all via `CodeGraphRepository`)

CLI: `relate` (`RelateCommand.java:108`), `impact` (`ImpactCommand.java:134`, BFS), `code-graph --cycles/--hotspots/--instability/--layers` (`CodeGraphCommand.java:82` → `DagRenderer.java:26`), `--cross-format` (`:194`), `describe` (`:460`), `health` (`:669` → `CodeHealthAnalyzer.java:30`), `gaps` (`:831`), `security --attack-surface` (`:1030` → `AttackSurfaceMapper.java:36`).
MCP: `relate`/`impact`/`code-graph` — shell to CLI above.
Internal readers: `DagRenderer`, `ModuleProfileComputer`, `AttackSurfaceMapper`, `QualityGapDetector`, `CodeHealthAnalyzer`.
NOT consumers (live regex/filename): `trace`, `which`, `cross-repo-deps`.

### C. AI tier — thin, mostly indirect

Backend clients (`ai/ClaudeClient`, `ai/OpenAiClient`, `ai/AiClient`): **0** code-graph refs.
- Only real path: `summary` (CLI) — `code_dependencies` → `SecurityAnalyzer.checkS009` `graph/SecurityAnalyzer.java:653` → `security_findings` → `SecurityPosture.query()` `:50` → `SummaryCommand.java:177` → `SummaryEngine.java:46` → `AiClient.generate()`.
- Fake-out: `explain` imports `RelationService.java:21` = live regex, not persisted graph.
- Not touching graph: `ask`, `perspectives`, `research`, `insights`, `enrich`, embeddings.
- Pre-existing (out of scope): MCP `handleSummary` `:1649` skips CLI's `SecurityPosture` injection.

### D. Persisted artifacts — TWO write channels (contract boundary)

SQLite, `db/migration/V13__code_knowledge_graph.sql` (+ `V14__ckg_repo_isolation.sql`). DB-only.

| Table | Key columns | Written by | Read by |
|---|---|---|---|
| `code_dependencies` | `source_file, source_class, source_package, target_file, target_class, target_package, dependency_type, is_external, repo_name, workspace_path` (`V13:7-19`,`V14:7-14`) | extractor (`upsertDependency`) | relate, impact, DagRenderer, ModuleProfileComputer, AttackSurfaceMapper, S009 |
| `cross_format_links` | `source_file, target_file, link_type, entity_name, workspace_path` (`V13:51-59`) | CrossFormatLinker (`batchInsertCrossFormatLinks`) | `code-graph --cross-format` |
| `module_profiles` | `fan_in, fan_out, instability, module_path, package_name, …` (`V13:28-45`) | ModuleProfileComputer | describe, DagRenderer, CodeHealthAnalyzer, QualityGapDetector |
| `code_quality_gaps` | `module_path, gap_type, severity, description, file_path, suggestion` (`V13:66-76`) | QualityGapDetector | `code-graph gaps` |

**Blast radius:** every §B/§C consumer couples to the SQLite tables, never to extractor internals. Seam refactor is contained inside `CodeGraphExtractor`; unchanged row shape = §B/§C untouched.

---

## Decision

**Extract a per-language `LanguageExtractor` seam (strategy registry). Each language = one impl of 3 capabilities; orchestrator keeps all shared concerns. Contract = the two §D write channels, not extractor internals. Cross-format stays OUTSIDE the seam.**

Sub-decisions (resolved via design grill):

| # | Question | Decision |
|---|---|---|
| 1 | `ResolutionKey` shape | **Sealed interface + records** — compile-time exhaustiveness protects behavior as languages are added; discriminator+`default` risks silent no-resolve. Java 21 (`pom.xml:31`). |
| 2 | `resolve()` location | **Own `Resolver` class** — single `resolve(ResolutionKey)` dispatching to the 4 *existing* algorithms verbatim (unify call site, not algorithms). Keeping it in orchestrator leaves the god-class problem. |
| 3 | Incremental | **One shared two-pass, no per-language hooks.** Contract: `findFiles()`+`declarations()` always full-workspace (index never stale); only `edges()` scoped to changed set. Maintainer confirms: incremental already rebuilds the full FQN map today — the always-full `declarations()` pass **codifies current cost, adds none**. |
| 4 | Cross-format | **Outside the seam** — category error: corpus substring scan (`CrossFormatLinker.java:56`), no `ResolutionKey` decls, writes a different table. Kept as a distinct orchestrator step. |

### Interface (pseudo-code)

```
sealed interface ResolutionKey permits FqnKey, PathKey, PackageKey {}
record FqnKey(String fqn)                    // Java, Kotlin   file-level
record PathKey(String modulePath)            // TS/JS          file-level
record PackageKey(String importPath, String pkg)  // Go        dir-level, many files -> 1 key

enum EdgeKind { IMPORT, SUPERTYPE, EMBED }   // no CROSS_FORMAT (outside seam)
record Declaration(ResolutionKey key, File file)
record RawEdge(ResolutionKey from, ResolutionRef to, EdgeKind kind)  // `to` unresolved

interface LanguageExtractor {
    String        languageId();
    Set<Ext>      extensions();
    Set<File>     findFiles(root, ExclusionRules excl);          // (1)
    List<Declaration> declarations(file, content);              // (2) -> shared Resolver
    Set<EdgeKind> supportedEdgeKinds();                          // (3) opt-in
    List<RawEdge> edges(file, content, List<Declaration> decls); // decls passed in (Q6)
}
```

### Implementations (pseudo-code)

```
Java/Kotlin:  FqnKey; supportedEdgeKinds={IMPORT,SUPERTYPE}
TypeScript:   PathKey; {IMPORT}
Go:           PackageKey (pkg name != last path segment); {IMPORT,EMBED}  // NO SUPERTYPE
              declarations: many files/dir -> same PackageKey
              edges: imports->IMPORT, struct embed->EMBED, implicit iface->emit nothing
```

### Orchestrator (`CodeGraphExtractor` shrinks to)

```
extract(root, conn, changed /*null=full*/):
    resolver = new Resolver()                         // owns Map<ResolutionKey,List<File>>
    work = []
    for lang in REGISTRY:                             // pass 1: ALWAYS full (index)
        for f in lang.findFiles(root, EXCL):
            decls = lang.declarations(f, read(f))
            resolver.addAll(decls); work.add(lang, f, decls)
    for (lang, f, decls) in work:                     // pass 2: edges (scoped if incremental)
        if changed != null and f not in changed: continue
        for e in lang.edges(f, read(f), decls):
            target = resolver.resolve(e.to)           // dispatch on key kind
            rows.add(CodeDependency(f, target, e.kind, ...))
    repo.upsertAll(conn, rows)
    crossFormat.run(root, conn, work)                 // separate step, writes cross_format_links

REGISTRY = [Java, Kotlin, TypeScript]  // + Go = zero orchestrator edit
```

### Open questions — resolved by maintainer review (PR #457)

**Q5 — Go package (dir, N files) → single `code_dependencies.target_file`.** Regex can't tell which file supplies the used symbol. **Resolved (maintainer): C, with a real-file fallback.**

Go's compilation unit *is* the package, so option A's fan-out is semantically defensible — but a 30-file package producing 30 edges per importer destroys impact's signal-to-noise, and C's honest misses fit the pinned-limitation culture here.
- A. **Fan-out** — one edge per file in the target package. Faithful, file-level for consumers; cost: importing a large package inflates edges with never-used targets.
- B. **Representative (synthetic)** — `target_file` = package directory path, one edge. **Rejected**: a directory path is not a real file, breaking the `target_file` invariant every §B consumer assumes (`relate` display, `impact` BFS, ADR §B:34).
- C. **Best-effort single** — resolve to the file whose declared symbol the import names, else `is_external`. Accurate when the name matches; regex-fragile, more honest misses.
- **Decision — C, fallback to a deterministic representative *real* file** (lexicographically-first non-test `.go` file in the target package), never a synthetic directory path. Preserves the "`target_file` is a real file" invariant; `target_package` carries the package-level truth in both the resolved and fallback cases. Index is `Map<Key,List<File>>` either way.

**Q6 — `edges()` needs `declarations()` (Kotlin supertypes are declarations).** **Resolved (maintainer): A.**
- A. **Pass decls in** — `edges(file, content, decls)` (current draft). No re-parse; only the same file's own decls are needed in pass 2.
- B. **Combined** — `extract(file, content) -> {decls, edges}`. One parse, but couples the two capabilities the seam split and fights the all-decls-before-resolve two-pass.
- C. **Re-parse** in `edges()` (status-quo double-parse). Simplest; accepted perf cost.
- **Decision — A** — kills the double-parse, keeps the two-pass + capability split.

### Test-coupling constraint (hard)

`CodeGraphExtractorTest.java` = 51 @Test. **Majority are white-box**, calling internal methods directly (`extractor.lookupBySimpleName`/`buildSimpleNameIndex` `:766-797`, `findKotlinTopLevelDecls_*` incl. pinned `..._known_limitation_constructor_default_value_call`). Moving these methods into `LanguageExtractor`/`Resolver` **modifies** those tests → "tests pass unmodified" (#428) only holds for the ~5 black-box `extractAndPersist_*` tests. **Resolved (maintainer): A — #428's constraint is amended.**
- A. Redefine "unmodified" = behavior tests (`extractAndPersist_*`) stay green; move white-box unit tests into per-language test classes alongside their methods (reviewed, expected).
- B. Keep `LanguageExtractor`/`Resolver` package-private in `graph` so existing tests reach methods unchanged — zero test diff, weaker encapsulation, all languages in one package.
- C. Leave thin delegator methods on `CodeGraphExtractor` — tests unchanged but vestigial methods defeat the shrink.
- **Decision — A.** #428's "tests pass unmodified" is amended to: *black-box behavior tests (`extractAndPersist_*`) pass unmodified; white-box unit tests relocate 1:1 with their methods into per-language test classes, assertions unchanged.* The pinned-limitation tests (e.g. `findKotlinTopLevelDecls_known_limitation_*`) move **with their regexes — relocation, never rewording.** Reject C.

### Behavioral gaps — our recommendation

**Resolved (maintainer):** seam PR **preserves** current behavior (faithful refactor, keeps black-box tests green); file each gap as a **separate follow-up issue** so "no behavior change" stays honest and the PR stays reviewable. Dispositions:

1. Dead `classToFile` param passed to `extractCrossFormatLinks` (`:233`), never read. **May ride inside the seam PR** — deleting a provably-unread parameter is behavior-neutral and does not taint the faithful-refactor claim.
2. `findYamlToJavaLinks` (`:113`) never called — cross-format is SQL-only. Follow-up (feature-coverage).
3. Cross-format never runs incrementally (`incrementalUpdate` hardcodes `crossLinks=0` `:355`). Follow-up (feature-coverage).
4. Incremental target-file staleness: unchanged file A importing new file B keeps stale `external` row (delete is `WHERE source_file` only). Correctness bug — filed **[#459](https://github.com/exoreaction/Synthesis/issues/459)**; lands regardless of the seam.
5. Deletions orphaned (`:279` skips `!Files.exists`; `ChangeSet.deleted()` never wired). Correctness bug — filed **[#460](https://github.com/exoreaction/Synthesis/issues/460)**; real from the extractor side alone (even when a deleted path is passed, `!Files.exists → continue` leaves the rows).
6. TS excluded from CLI `--incremental` (`CodeGraphCommand.java:362`). **Drift-shaped**, not mere feature-coverage: silently dropping TypeScript means a quietly stale TS graph. Follow-up, filed with that framing.

## Alternatives Considered

| Option | Why rejected |
|--------|--------------|
| **A. `LanguageExtractor` interface + registry** | **Chosen.** |
| B. Abstract base + template methods | Shared base drifts to god-class; inheritance couples languages. |
| C. Split file only (regions) | No real seam — 4th language still edits shared file. |
| D. Replace regex with parsers | Out of scope per #428. |

## Stress-test — Go (acceptance criterion)

- find-files: trivial (`*.go`, excl `_test.go`/`vendor/`).
- resolution: package=directory + module-path imports (pkg id ≠ last segment) → needs package granularity, not 2-way FQN/path.
- edges: no supertypes; struct embedding = EMBED; implicit interface satisfaction = un-regex-able → emit subset.

**Accept iff Go slots in as a new `LanguageExtractor` with zero orchestrator edits.**

### Acceptance criterion (mechanical)

Go lands as `LanguageExtractor` #4 behind this seam. The zero-orchestrator-edit claim is **verifiable, not aspirational**: the Go PR's diff must not touch `CodeGraphExtractor.java` outside the single `REGISTRY` registration line. A diff that edits the orchestrator anywhere else means the seam failed to hold — a reviewer can hold the Go PR to this with `git diff` alone.

## Consequences

- **+** 4th language = one file + registration; consumers/AI insulated (contract = tables); per-language tests localize.
- **−** Up-front cost to make resolution model + opt-in edge-kinds expressive for Go; one indirection layer.
- **Risk** resolution abstraction too narrow (Go proves 2-way insufficient); silent drift — gated by black-box `extractAndPersist_*` tests.

## Review Trigger

Revisit if: a language needs data `code_dependencies` can't hold (schema = contract change); the regex-vs-parser exclusion is reconsidered; a 5th language needs orchestrator edits.
