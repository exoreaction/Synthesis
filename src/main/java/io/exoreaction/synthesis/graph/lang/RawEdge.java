package io.exoreaction.synthesis.graph.lang;

/**
 * A dependency edge emitted by a language extractor's {@code edges()} pass, with
 * its target ({@link #to}) still unresolved. The orchestrator resolves {@code to}
 * to a target file and writes a {@code code_dependencies} row.
 *
 * <p>Carries every field needed to reproduce that row byte-for-byte:
 * {@code sourceClass}/{@code sourcePackage} (the owning declaration),
 * {@code targetClass}/{@code targetPackage} (display attributes computed verbatim
 * by the language), the abstract {@link EdgeKind}, and — separately — the exact
 * persisted {@code dependencyType} string ({@code "import"}/{@code "extends"}/
 * {@code "implements"}/{@code "supertype"}), which {@link EdgeKind} cannot encode.
 */
public record RawEdge(
        String sourceClass,
        String sourcePackage,
        ResolutionRef to,
        String targetClass,
        String targetPackage,
        EdgeKind kind,
        String dependencyType) {}
