# ADR-0001 Implementation Plan: per-language extraction seam

**Date:** 2026-07-24
**ADR:** [ADR-0001](ADR-0001-codegraphextractor-per-language-seam.md) (Accepted, merged in PR #457)
**Issue:** [#463](https://github.com/exoreaction/Synthesis/issues/463) (implementation) — parent [#428](https://github.com/exoreaction/Synthesis/issues/428)
**Branch:** `463-language-extractor-seam`

This plan restates **only** what ADR-0001 and the maintainer review ([PR #457](https://github.com/exoreaction/Synthesis/pull/457))
specify. Anything the ADR does not rule on is listed under "Open — confirm before coding",
not assumed.

## Regression proof (exactly the ADR's mechanism — no extra instrument)

Per the amended test-coupling constraint (ADR "Test-coupling — Decision A"):
- **black-box behavior tests (`extractAndPersist_*`) pass unmodified** — they are the proof
  of no-behavior-change and stay green after every step;
- **white-box unit tests relocate 1:1** with their methods into per-language test classes,
  **assertions unchanged**;
- **pinned-limitation tests** (`findKotlinTopLevelDecls_known_limitation_*`) move **with
  their regexes — relocation, never rewording**.

Baseline: `CodeGraphExtractorTest` = 51 @Test. Gate after every step:
```
mvn test -Dtest=CodeGraphExtractorTest -q
```
Green → commit → next step. Red → revert step.

## Contract mandated by the ADR

### Types (ADR "Interface" pseudo-code) — verbatim shapes
- `sealed interface ResolutionKey permits FqnKey, PathKey, PackageKey`
- `record FqnKey(String fqn)` — Java, Kotlin (file-level)
- `record PathKey(String modulePath)` — TS/JS (file-level)
- `record PackageKey(String importPath, String pkg)` — Go, dir-level *(see Open #2)*
- `enum EdgeKind { IMPORT, SUPERTYPE, EMBED }` — no CROSS_FORMAT
- `record Declaration(ResolutionKey key, File file)`
- `record RawEdge(ResolutionKey from, ResolutionRef to, EdgeKind kind)` — `to` unresolved

### Seam interface (ADR) — six methods, exact
```
interface LanguageExtractor {
    String            languageId();
    Set<Ext>          extensions();
    Set<File>         findFiles(root, ExclusionRules excl);
    List<Declaration> declarations(file, content);
    Set<EdgeKind>     supportedEdgeKinds();
    List<RawEdge>     edges(file, content, List<Declaration> decls);  // Q6=A: decls passed in
}
```

### Resolver (ADR sub-decision 2)
Own `Resolver` class. Single `resolve(ResolutionKey)` **dispatching to the existing
algorithms verbatim** (unify the call site, do not rewrite the algorithms). Owns the
`Map<ResolutionKey, List<File>>` index (+ the existing simple-name index).

### Implementations (ADR "Implementations")
- Java: `FqnKey`; `supportedEdgeKinds = {IMPORT, SUPERTYPE}`
- Kotlin: `FqnKey`; `{IMPORT, SUPERTYPE}`
- TypeScript: `PathKey`; `{IMPORT}`
- Go: **not in this PR** — `REGISTRY = [Java, Kotlin, TypeScript]`

### Orchestrator (ADR "Orchestrator" pseudo-code)
`CodeGraphExtractor` shrinks to the shared two-pass:
- **Pass 1 (always full):** for each lang in REGISTRY → `findFiles` → `declarations` →
  add to `Resolver`; record `(lang, file, decls)`.
- **Pass 2 (scoped when incremental):** for each recorded file, skip if `changed != null
  && file ∉ changed`; `edges(file, content, decls)` → `resolve(e.to)` → upsert rows.
- Cross-format runs as a **separate** orchestrator step (ADR sub-decision 4), writes
  `cross_format_links`.

### Gaps (ADR "Behavioral gaps")
- **Gap #1** (dead `classToFile` param on `extractCrossFormatLinks`, `:233`) — **delete it
  inside this PR** (behavior-neutral, ADR-permitted).
- **Gaps #2–#6** — **do not touch.** Follow-ups. #4/#5 are #459/#460.

### Acceptance (ADR "Acceptance criterion (mechanical)")
After the refactor, `CodeGraphExtractor` holds **no per-language extraction code**: a future
Go extractor must slot in touching the orchestrator only at the single `REGISTRY` line
(`git diff`-verifiable).

## Execution order (working sequence; gate = regression proof above)

1. Value types (`ResolutionKey` + records, `EdgeKind`, `Declaration`, `RawEdge`, `ResolutionRef`). Compile only.
2. `Resolver` — extract existing resolution algorithms verbatim; relocate their white-box tests 1:1.
3. `LanguageExtractor` interface.
4. `JavaLanguageExtractor` — move Java extraction; relocate Java white-box tests.
5. `KotlinLanguageExtractor` — move Kotlin extraction; relocate white-box + pinned tests (regexes intact).
6. `TypeScriptLanguageExtractor` — move TS extraction; relocate TS white-box tests.
7. Rewire orchestrator to the two-pass over `REGISTRY`; keep cross-format separate; delete gap #1 param.
8. Full `mvn test`; confirm orchestrator has no per-language code (acceptance).

Each step: one commit, `extractAndPersist_*` + relocated tests green.

## Open — confirm before coding (ADR does NOT rule these; do not assume)

1. **Package location of the new types/classes.** ADR ruling A chose to *relocate tests into
   per-language test classes* but never named a production package. Options: keep in
   `io.exoreaction.synthesis.graph` (no new package) vs a new `graph.lang` subpackage.
   *No default taken here.*
2. **`PackageKey` / Go scaffolding now vs Go PR.** ADR pseudo-code lists `PackageKey` in the
   sealed `permits`, but `REGISTRY` excludes Go and Q5's fallback is a Go-only concern.
   Decide: include `PackageKey` in the sealed interface now (Resolver leaves it unhandled
   until Go lands) vs add `PackageKey` in the Go PR. *No default taken here.*
3. **`ResolutionRef`, `Ext`, `ExclusionRules` concrete shapes.** ADR pseudo-code names them
   but does not define fields — design detail to settle at step 1.
