# ADR-0001: Per-language extraction seam for CodeGraphExtractor

**Date:** 2026-07-20
**Status:** Proposed
**Issue:** [#428](https://github.com/exoreaction/Synthesis/issues/428)

## Context

`CodeGraphExtractor.java` now holds **three** languages of regex-based extraction inline — Java, TypeScript (#323), Kotlin (#406) — at ~1,150 lines. Three is the point where "one more language shaped like the last one" stops scaling; a fourth (Go is the concrete motivator, see §Stress-test) would make the single file untenable.

Issue #428 proposes extracting a per-language seam. This is a **design task**, not a mechanical refactor: the hard part is defining the per-language contract and the orchestrator boundary such that a new language slots in without touching consumers. "No behavior change / existing tests pass unmodified" is a *constraint* on the design, not evidence the work is mechanical.

This ADR records **the complete integration inventory** for the code-extraction subsystem (verified by source read, file:line below) so it is not re-derived, then states the seam decision that inventory supports.

---

## Inventory (verified 2026-07-20, repo @ `main` `b172864`)

Source of truth. Every downstream design/impl step reads from here rather than re-scanning.

### A. Producers — write extracted code data

Center: `src/main/java/io/exoreaction/synthesis/graph/CodeGraphExtractor.java:32`
- `extractAndPersist(Path, Connection)` — full scan+persist — `:142`
- `incrementalUpdate(Path, Connection, Set<Path>)` — git-aware incremental — `:249`
- Per-language extraction inline:
  - Java structural deps (import/extends/implements regex) — `extractStructuralDeps` `:628`
  - TypeScript/JS (`JS_TS_IMPORT` regex `:60`) — `extractTypeScript` `:787`, `extractTypeScriptFile` `:809`
  - Kotlin (`KOTLIN_IMPORT`/`KOTLIN_TOPLEVEL_DECL` regex `:70-103`) — `extractKotlinImports` `:954`, `extractKotlinFiles` `:1109`, `extractKotlinFile` `:1137`
  - Cross-format (SQL/YAML → Java) — delegates to `CrossFormatLinker.java:20` (`findSqlToJavaLinks` `:56`, `findYamlToJavaLinks` `:113`)
- File filters — `isBuildArtifact` `:597`, `isArchiveDirectory` `:618`
- Internal scratch — `simpleNameIndex` (extractor-private; resolves simple names → files; **no external reader**, refs only inside `CodeGraphExtractor.java` e.g. `:165,204,215,265,304,343,513,632,1112`)

**Writers / DAO:** `graph/CodeGraphRepository.java` — `upsertDependency` `:62`, `upsertCrossFormatLink` `:89`, `deleteDependenciesForFile` `:201`, `deleteAllDependencies` `:214`, `batchInsertCrossFormatLinks` `:231`, `upsertQualityGap` `:367`
**Derived-table producers (post-extraction pipeline stage):** `ModuleProfileComputer.java:29` (`computeAndPersist` `:53`), `QualityGapDetector.java:28` (`detectAndPersist` `:47`), `CompletenessScorer` (pipeline stage)

**CLI that trigger extraction:**
| Command | File:line |
|---|---|
| `code-graph extract` (`--incremental`/`--dry-run`/`--stats`) | `cli/CodeGraphCommand.java:230` (`ExtractSub`), calls `:340`/`:370` |
| `code-graph describe --refresh` | `cli/CodeGraphCommand.java:431` (`DescribeSub`), calls `:474` |
| `code-graph health --refresh` | `cli/CodeGraphCommand.java:641` (`HealthSub`), calls `:680` |
| `code-graph gaps --refresh` | `cli/CodeGraphCommand.java:781` (`GapsSub`), calls `:835` |
| `maintain` Phase 10 "Code Graph" | `cli/MaintainOrchestrator.java:980` (`runCodeGraph`), calls `:1031`/`:1034` |

- `code-graph security --refresh` does **NOT** call the extractor (re-runs `SecurityAnalyzer` only) — `cli/CodeGraphCommand.java:967`.
- `relate --refresh` / `impact` use a separate **non-persisting live-extraction** path (`RelationService`), not `CodeGraphExtractor`.

**MCP that trigger extraction:** `code-graph` tool → `mcp/SynthesisToolHandler.java:2411` (`handleCodeGraph`); builds `code-graph <subcommand> <flags>` and shells out via `runSynthesisCli` (indirect — hits the CLI paths above). Registered `mcp/SynthesisMCPServer.java:518`, dispatched `:863`. Documented `subcommand` enum omits `extract` but `flags` is free-text forwarded verbatim, so persisting `describe/health/gaps --refresh` are reachable.

### B. Consumers — read extracted code data

All reads route through `CodeGraphRepository` (`getDependenciesFrom` `:110`, `getDependenciesTo` `:124`, `getIncomingForFile` `:169`, `getAllDependencies` `:182`, `getCrossFormatLinks` `:303`, `getQualityGaps*` `:392-479`, `countDependencies` `:328`, `isPopulated` `:354`).

**CLI:**
| Command | File:line | Reads |
|---|---|---|
| `relate` | `cli/RelateCommand.java:108-133` | `code_dependencies` |
| `impact` | `cli/ImpactCommand.java:134-167` | `code_dependencies` (BFS blast-radius) |
| `code-graph` (`--cycles`/`--hotspots`/`--instability`/`--layers`, default DAG) | `cli/CodeGraphCommand.java:82-160` | `code_dependencies` + `module_profiles` via `DagRenderer.java:26` |
| `code-graph --cross-format` | `cli/CodeGraphCommand.java:194` | `cross_format_links` |
| `code-graph describe` | `cli/CodeGraphCommand.java:460` | `module_profiles` (fallback `code_dependencies`) |
| `code-graph health` | `cli/CodeGraphCommand.java:669` → `CodeHealthAnalyzer.java:30` | `module_profiles`-derived |
| `code-graph gaps` | `cli/CodeGraphCommand.java:831` | `code_quality_gaps` |
| `code-graph security --attack-surface` | `cli/CodeGraphCommand.java:1030` → `AttackSurfaceMapper.java:36` | `code_dependencies` (BFS) |
| `code-graph extract --stats` | `cli/CodeGraphCommand.java:294` | read-after-write counts |

**MCP:** `relate` (`SynthesisMCPServer.java:353`), `impact` (`:455` → handler `SynthesisToolHandler.java:2196`), `code-graph` (`:518` → `:2411`) — all shell to the CLI above.

**Internal readers:** `DagRenderer.java:26` (cycles/layers/DAG; raw SQL `loadInternalPackageEdges` `:322`), `ModuleProfileComputer.java:29` (aggregates `code_dependencies` → `module_profiles`), `AttackSurfaceMapper.java:36` (BFS cli/mcp→sinks), `QualityGapDetector.java:28`, `CodeHealthAnalyzer.java:30`.

**Look-like-consumers that AREN'T** (live regex/filename, bypass persisted graph): `trace` (`cli/TraceCommand.java`, uses `RelationService`), `which` (`cli/WhichCommand.java`, filename-only), `cross-repo-deps` (`cli/CrossRepoDepsCommand.java`, zero graph refs).

### C. AI tier over extracted code — THIN, mostly indirect

Backend clients `ai/ClaudeClient.java`, `ai/OpenAiClient.java`, `ai/AiClient.java`: **0** code-graph references (grep) — take pre-built prompt strings only.

- **Only real path:** `summary` (CLI) — `code_dependencies` → `SecurityAnalyzer.checkS009` `graph/SecurityAnalyzer.java:653` → `security_findings` → `SecurityPosture.query()` `graph/SecurityPosture.java:50` → `cli/SummaryCommand.java:177` → `SummaryEngine.java:46` → `AiClient.generate()`. Indirect, security-chain only.
- **Fake-out:** `explain` (CLI `cli/ExplainCommand.java:12` + MCP `handleExplain` `SynthesisToolHandler.java:1502`) imports `RelationService.java:21` but that does **live regex** parsing of raw files, not the persisted graph. Injected into prompt at `ai/CodeExplainer.java:323`.
- **Not touching graph:** `ask`, `perspectives`, `research`, `insights`, `enrich`, embeddings (`ai/EmbeddingService.java:137` embeds raw file text, 0 graph refs) — Lucene/raw-text grounded only.
- **MCP discrepancy (pre-existing, out of #428 scope):** MCP `handleSummary` `SynthesisToolHandler.java:1649` does NOT replicate CLI `summary`'s `SecurityPosture` injection — MCP summary never touches the code-graph.

### D. Persisted artifacts (the contract boundary)

All SQLite, migration `src/main/resources/db/migration/V13__code_knowledge_graph.sql` (+ repo-isolation `V14__ckg_repo_isolation.sql`). DB-only — no on-disk index files.

| Table | Key columns | Written by | Read by |
|---|---|---|---|
| `code_dependencies` | `workspace_path, repo_name, source_file, source_class, source_package, target_file, target_class, target_package, dependency_type, is_external, last_computed` (`V13:7-19`, `V14:7-14`) | extractor | relate, impact, DagRenderer, ModuleProfileComputer, AttackSurfaceMapper, QualityGapDetector, SecurityAnalyzer-S009 |
| `cross_format_links` | `workspace_path, source_file, target_file, link_type, entity_name, last_computed` (`V13:51-59`) | CrossFormatLinker | `code-graph --cross-format` |
| `module_profiles` | `fan_in, fan_out, instability, module_path, package_name, inferred_purpose, …` (`V13:28-45`) | ModuleProfileComputer | describe, DagRenderer, CodeHealthAnalyzer, QualityGapDetector |
| `code_quality_gaps` | `module_path, gap_type, severity, description, file_path, suggestion, last_computed` (`V13:66-76`) | QualityGapDetector | `code-graph gaps` |

---

## Decision

**Extract a per-language extraction seam. Each language provides the three capabilities the extractor already exercises informally; the orchestrator retains all shared concerns. The stable contract is the `code_dependencies` row shape (Inventory §D), not the extractor's internals.**

Seam (one implementation per language):
1. **find files** — extension walk + shared exclusion rules
2. **contribute to resolution** — declarations → name/path index (must express *both* FQN-per-file, Java/Kotlin, *and* path-per-file, TS — and package-per-directory, see Go)
3. **extract edges** — imports/supertypes → `code_dependencies` rows, where *which edge kinds a language emits is itself variable* (see Go)

Orchestrator (`CodeGraphExtractor`) keeps: full-vs-`--incremental` control, shared `simpleNameIndex`, package accounting, stats, and all persistence via `CodeGraphRepository`.

**Blast-radius finding (from Inventory):** every consumer (§B) and the one real AI path (§C) couples to the SQLite *tables* (§D), never to extractor internals. Therefore the seam refactor is contained **inside `CodeGraphExtractor`**; as long as row shape is unchanged, §B/§C are untouched and the #406 pinned-limitation tests remain the enforceable behavior spec. "No behavior change" is verifiable at the DB boundary.

The specific interface shape (below) is the open sub-decision; the pseudo-code is **tentative** and refined as discussion continues — not yet ratified.

### Tentative interface (pseudo-code — to refine)

```
interface LanguageExtractor {
    String   languageId()
    Set<Ext> extensions()                       // .java / .go / ...

    // (1) find files — orchestrator passes shared exclusions
    Set<File> findFiles(root, ExclusionRules excl)

    // (2) contribute resolution — declarations into the SHARED index.
    //     Key carries granularity so orchestrator resolves uniformly.
    List<Declaration> declarations(file, content)

    // (3) extract edges — language declares WHICH kinds it emits (opt-in),
    //     then emits raw (unresolved) edges; orchestrator resolves + persists.
    Set<EdgeKind>  supportedEdgeKinds()
    List<RawEdge>  edges(file, content)
}
```

The two design tensions (resolution granularity, variable edge kinds) live in two sum-types:

```
// granularity-agnostic address — the ONE place FQN/path/package coexist
ResolutionKey =
    | FqnKey(fqn)                     // Java, Kotlin   io.x.Foo   (file-level)
    | PathKey(modulePath)            // TS/JS          ./foo/bar (file-level)
    | PackageKey(importPath, pkg)    // Go             dir-level, many files -> 1 key

EdgeKind = IMPORT | SUPERTYPE | EMBED | CROSS_FORMAT

Declaration { ResolutionKey key; File file }
RawEdge     { ResolutionKey from; ResolutionRef to; EdgeKind kind }   // `to` unresolved
```

### Tentative implementations (pseudo-code)

```
JavaExtractor:
    extensions         = {.java}
    supportedEdgeKinds = {IMPORT, SUPERTYPE}
    declarations(f,c)  = [ Declaration(FqnKey(pkg+"."+class), f) ]
    edges(f,c):
        imports            -> RawEdge(FqnKey(self), FqnRef(imported), IMPORT)
        extends/implements -> RawEdge(FqnKey(self), FqnRef(super),    SUPERTYPE)

KotlinExtractor:                        // same shape as Java
    extensions         = {.kt}
    supportedEdgeKinds = {IMPORT, SUPERTYPE}
    ... FqnKey ...

TypeScriptExtractor:
    extensions         = {.ts,.tsx,.js,.jsx}
    supportedEdgeKinds = {IMPORT}       // no supertype edges today
    declarations(f,c)  = [ Declaration(PathKey(fileModulePath), f) ]
    edges(f,c):
        JS_TS_IMPORT -> RawEdge(PathKey(self), PathRef(resolveRel(imp)), IMPORT)

GoExtractor:                            // the acceptance-test language (§Stress-test)
    extensions         = {.go}   excl: _test.go, vendor/
    supportedEdgeKinds = {IMPORT, EMBED}     // NO SUPERTYPE
    declarations(f,c):
        pkg = parsePackageClause(c)          // package name != last path segment
        return [ Declaration(PackageKey(dirImportPath(f), pkg), f) ]
        //   ^ many files in a dir emit the SAME key -> package granularity
    edges(f,c):
        importStrings -> RawEdge(PackageKey(self), PackageRef(importPath), IMPORT)
        structEmbeds  -> RawEdge(PackageKey(self), FqnRef(embedded),       EMBED)
        // interface satisfaction: IMPLICIT, no keyword -> emit NOTHING. Legal.
```

### Tentative orchestrator (`CodeGraphExtractor` shrinks to this)

```
extractAndPersist(root, conn):
    index = SharedResolutionIndex()          // absorbs simpleNameIndex + fqn/path/pkg maps
    work  = []

    for lang in REGISTRY:                     // pass 1: build shared index
        for f in lang.findFiles(root, EXCL):
            c = read(f)
            for d in lang.declarations(f, c): index.add(d.key, d.file)
            work.add(lang, f, c)

    rows = []
    for (lang, f, c) in work:                 // pass 2: resolve + emit
        for e in lang.edges(f, c):
            target = index.resolve(e.to)      // ONE resolver, all key kinds
            rows.add(CodeDependency(f, target, e.kind, ...))   // contract row, unchanged

    repo.upsertAll(conn, rows)                // §D boundary untouched

REGISTRY = [Java, Kotlin, TypeScript]
// add Go:  REGISTRY += GoExtractor          <- zero orchestrator edit. Seam proven.
```

**Open questions for later discussion** (do not treat as settled):
- `ResolutionKey` as sealed sum-type vs a single struct with a `kind` discriminator.
- Where `index.resolve()` lives — the one spot all key kinds converge; keep in orchestrator or its own `Resolver`.
- Whether `incrementalUpdate` reuses the same two-pass shape or needs a per-language incremental hook.
- Cross-format (SQL/YAML) — a `LanguageExtractor` with empty declarations + `{CROSS_FORMAT}` edges, or kept separate.

## Alternatives Considered

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| **A. `LanguageExtractor` interface + strategy registry** (chosen direction) | Clean per-language files; new language = new class + register; orchestrator closed for modification; edge-kinds expressible as capability the impl opts into | Must design resolution model rich enough for FQN + path + package granularity up front | **Recommended** |
| B. Abstract base class with template methods | Shares helper code via inheritance | Regex helpers differ enough per language that shared base tends toward a god-class; inheritance couples languages | Rejected — recreates the coupling #428 is removing |
| C. Leave inline, split file only (partial classes / regions) | Minimal change | Doesn't create a real seam; 4th language still edits shared file — fails the motivation | Rejected — cosmetic, not structural |
| D. Replace regex with real parsers (tree-sitter etc.) | Accurate extraction | Explicitly **out of scope** per #428; regex + pinned-limitation tests is the accepted trade-off | Rejected — different decision |

## Stress-test — Go as the 4th-language acceptance criterion

Go is the deliberate adversarial pick; it breaks assumptions Java/Kotlin/TS share and is the design's acceptance test.

- **find-files:** trivial (`*.go`, exclude `_test.go`, `vendor/`). No pressure.
- **contribute-resolution — new axis:** unit is **package = directory**, not file (many files, one package); imports are **module-path strings** where the package identifier can differ from the last path segment or be aliased. Neither FQN-per-file nor plain path-per-file. → the resolution model in capability (2) must express package-vs-file granularity, not just a 2-way FQN/path split.
- **extract-edges — the real stress:** Go has **no supertype declarations**. Imports are regex-able; the inheritance analog is **struct embedding** (different shape from `extends`/`implements`); **interface satisfaction is implicit/structural** — no keyword to regex at all. → capability (3) must let a language emit a **subset** of edge kinds; "extract supertypes" cannot be mandatory.

**Acceptance:** the chosen seam is correct iff Go slots in as a new `LanguageExtractor` with **zero orchestrator changes**. If it forces an orchestrator edit, the boundary is drawn wrong.

## Consequences

**Positive:**
- 4th+ language = one new file + registration; `CodeGraphExtractor` shrinks to orchestration.
- Consumers/AI tier provably insulated (contract = DB tables).
- Per-language pinned-limitation tests localize to per-language units.

**Negative:**
- Up-front design cost to make the resolution model + variable edge-kinds expressible enough for Go without over-engineering for languages not yet added.
- One more indirection layer between orchestrator and regex.

**Risks:**
- Designing the resolution abstraction too narrowly (2-way FQN/path) — Go already proves that insufficient; mitigated by using Go as a design input, not just a later test.
- Silent behavior drift during extraction — mitigated by the #406 pinned tests passing unmodified as the gate.

## Review Trigger

Revisit if: (a) a language needs extraction data the `code_dependencies` schema can't hold (schema change = contract change = re-open §D); (b) the regex-vs-parser trade-off (#428 scope exclusion) is reconsidered; (c) a 5th language doesn't fit the seam without orchestrator edits.
