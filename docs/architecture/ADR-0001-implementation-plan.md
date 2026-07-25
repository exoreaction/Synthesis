# ADR-0001 Implementation Plan: per-language extraction seam

**Date:** 2026-07-24
**ADR:** [ADR-0001](ADR-0001-codegraphextractor-per-language-seam.md) (Accepted, merged in PR #457)
**Issue:** [#463](https://github.com/exoreaction/Synthesis/issues/463) (implementation) — parent [#428](https://github.com/exoreaction/Synthesis/issues/428)
**Branch:** `463-language-extractor-seam`

This plan restates **only** what ADR-0001, the maintainer review ([PR #457](https://github.com/exoreaction/Synthesis/pull/457)),
and the scoped issue [#463](https://github.com/exoreaction/Synthesis/issues/463) specify. Anything none of
them rule on is listed under "Open — confirm before coding", not assumed.

**Contract (issue #463):** pure structural refactor of the **three existing** languages (Java, Kotlin,
TypeScript) behind the seam. `code_dependencies` + `cross_format_links` row output stays **byte-identical**.

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
- `sealed interface ResolutionKey permits FqnKey, PathKey` — **PackageKey NOT added now** (issue #463: Go/`PackageKey` out of scope; the sealed `permits` line is a one-line, non-orchestrator edit in the Go PR)
- `record FqnKey(String fqn)` — Java, Kotlin (file-level)
- `record PathKey(String modulePath)` — TS/JS (file-level)
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

### Acceptance (ADR "Acceptance criterion (mechanical)" = issue #463 Definition of Done)
- Black-box `extractAndPersist_*` tests pass **unmodified**.
- Full `mvn test` green.
- `CodeGraphExtractor` holds **no per-language extraction code**: a future Go extractor slots in
  touching the orchestrator only at the single `REGISTRY` line (`git diff`-verifiable).

## Execution order (working sequence; gate = regression proof above)

**Sequencing = per-language vertical slices.** Each language step both moves the impl AND rewires
the orchestrator for that language, so inline code and its white-box tests move together (no interim
duplication, gate green every step). Shared exclusion predicates `isBuildArtifact`/`isArchiveDirectory`
**stay public-static on `CodeGraphExtractor`** — orchestrator-shared, external callers, not per-language.

1. ✅ Value types (`ResolutionKey`+records, `EdgeKind`, `Declaration`, `RawEdge`, `ResolutionRef`). Compile only. `883806f`
2. ✅ `Resolver` — FQN/simple-name algorithms verbatim; 5 white-box tests → `ResolverTest`. `3c105f2`
3. ✅ `LanguageExtractor` interface + `Ext` + `ExclusionRules`. Pure additions. `53f3169`
4. ✅ `JavaLanguageExtractor` slice — impl + two-pass + inline-Java delete + 13 tests relocated. `4be2cb0`
5. ✅ `KotlinLanguageExtractor` slice — added; inline Kotlin deleted; 18 tests relocated (pinned regexes intact). `a4f2753`
6. ✅ `TypeScriptLanguageExtractor` slice — added; TS path resolution → `Resolver`; inline TS deleted.
   Prereq gate: 6 TS characterization tests written first against inline TS (`6ecff40`), since TS had
   zero coverage (safety-net-first). `66f0c8d`
7. ✅ Orchestrator tidy — inlined `REGISTRY` loop + `extractorFor` dispatch; gap #1 param deleted;
   stale javadocs/imports cleaned. `88f1031`
8. ✅ Full `mvn test` 4803/0; orchestrator holds no per-language extraction code (467 lines from ~1150).

Each step: one commit, `extractAndPersist_*`/`incrementalUpdate_*` + relocated tests green.

## Resolved

- **Package location = `io.exoreaction.synthesis.graph.lang`** (new subpackage). Seam types,
  `LanguageExtractor`, `Resolver`, and per-language impls live here with a public API surface
  (matches ADR test-coupling ruling A; rejects option B's package-private-in-`graph`). *(was Open #1)*
- **`PackageKey` / Go — deferred (issue #463).** Issue #463 rules Go/`PackageKey` **out of scope**; the seam
  must accept it with zero orchestrator edits. `PackageKey` is added in the Go PR (one-line
  `permits` edit in `ResolutionKey`, not an orchestrator edit). *(was Open #2)*

## Resolver-population hooks (ADR left the mechanism open; user-approved, consistent)

The ADR says the `Resolver` owns the indexes for its **4 existing algorithms** (ADR:71) but does
not say how the two non-FQN indexes get populated. Resolved consistently (both = an opt-in default
hook on `LanguageExtractor`; orchestrator merges generically; non-contributing languages leave empty;
Go stays a one-line REGISTRY add):
- **Kotlin** top-level-function fallback (algorithm #3) → `packageFallbackFiles(file, content, decls)`.
- **TypeScript** path index (algorithm #4) → `pathIndex(root, file, content)`; plus `Resolver.resolve`
  gains a `sourceRelPath` param (orchestrator supplies it) since TS resolution is path-relative.
Core 6 seam methods unchanged; the deviations are the population detail the ADR under-specified,
resolved in the direction the ADR points (orchestrator generic, languages self-contained).

## Settled at step 1 — value-type shapes (faithful to existing code)

Read of `CodeGraphExtractor` surfaced 3 hard faithfulness constraints (none in ADR pseudo-code):
- **`EdgeKind` ≠ `dependency_type`.** Rows store `"import"`/`"extends"`/`"implements"`/`"supertype"`
  (`:194,646,662,1202`; test asserts exact strings `:556,558`). `RawEdge` carries the literal
  `dependencyType`; `EdgeKind{IMPORT,SUPERTYPE,EMBED}` is only the abstract opt-in category.
- **Kotlin function-fallback stays per-language.** Java import resolve does NOT apply
  `packageFunctionFiles` (`:187`); Kotlin does (`:1173`). `FqnRef` carries a
  `packageFunctionFallback` flag so a merged resolve can't change mixed-repo behavior.
- **3 resolution algorithms, not 1 map.** FQN-exact, simple-name-in-package, TS-path → 3 `ResolutionRef` subtypes.

`Declaration`/`RawEdge` use `java.nio.file.Path` (whole codebase is `Path`), not pseudo-code's `File`.

```java
sealed interface ResolutionKey permits FqnKey, PathKey {}
  record FqnKey(String fqn) implements ResolutionKey {}      // Java, Kotlin
  record PathKey(String modulePath) implements ResolutionKey {} // TS stem
sealed interface ResolutionRef permits FqnRef, SimpleNameRef, ModulePathRef {}
  record FqnRef(String fqn, boolean packageFunctionFallback) implements ResolutionRef {} // Java=false, Kotlin=true
  record SimpleNameRef(String simpleName, String sourcePackage) implements ResolutionRef {} // extends/implements/supertype
  record ModulePathRef(String specifier, String sourceRelPath) implements ResolutionRef {} // TS import
enum EdgeKind { IMPORT, SUPERTYPE, EMBED }
record Declaration(ResolutionKey key, Path file) {}
// carries every field for a byte-identical code_dependencies row:
record RawEdge(String sourceClass, String sourcePackage,
               ResolutionRef to, String targetClass, String targetPackage,
               EdgeKind kind, String dependencyType) {}
```

`Ext` + `ExclusionRules` land with the interface (step 3), not step 1.

## Open — design detail, settle when reached

1. **`Ext`, `ExclusionRules` concrete shapes (step 3).** ADR pseudo-code names them
   but does not define fields.
