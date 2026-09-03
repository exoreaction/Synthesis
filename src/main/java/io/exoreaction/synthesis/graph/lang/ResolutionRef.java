package io.exoreaction.synthesis.graph.lang;

/**
 * An unresolved use-site reference — the {@code to} side of a {@link RawEdge}
 * before the shared {@code Resolver} maps it to a target file.
 *
 * <p>Three subtypes mirror the three resolution algorithms already present in
 * {@code CodeGraphExtractor}; the resolver dispatches on subtype to each existing
 * algorithm verbatim (ADR-0001 sub-decision 2). They are deliberately distinct
 * from {@link ResolutionKey}: a reference carries more context than a declared
 * identity (source package for simple-name lookups, source path for TS).
 */
public sealed interface ResolutionRef
        permits ResolutionRef.FqnRef, ResolutionRef.SimpleNameRef, ResolutionRef.ModulePathRef {

    /**
     * FQN import reference (Java, Kotlin). Resolves via exact FQN lookup.
     *
     * @param fqn                     the imported fully-qualified name
     * @param packageFunctionFallback when {@code true} (Kotlin), fall back to the
     *        single function-only file in the imported symbol's package if the FQN
     *        lookup misses. Java passes {@code false} — it never applied this fallback,
     *        and enabling it would change mixed-repo behavior.
     */
    record FqnRef(String fqn, boolean packageFunctionFallback) implements ResolutionRef {}

    /**
     * Simple-name reference used for extends/implements/supertype edges, where the
     * source only names the type by its simple name. Resolves via the simple-name
     * index, preferring a candidate in {@code sourcePackage}.
     */
    record SimpleNameRef(String simpleName, String sourcePackage) implements ResolutionRef {}

    /**
     * TypeScript module-specifier reference (e.g. {@code './Foo.js'}). Resolves against
     * the TS path index, relative to the source file's directory. The source file's
     * workspace-relative path is supplied to {@code Resolver.resolve} at call time (the
     * orchestrator already has it), so it is not stored on the ref.
     */
    record ModulePathRef(String specifier) implements ResolutionRef {}
}
