package io.exoreaction.synthesis.graph.lang;

/**
 * Abstract category of a dependency edge, used for a language's opt-in
 * {@code supportedEdgeKinds()} (ADR-0001). This is NOT the persisted
 * {@code code_dependencies.dependency_type} string: that exact value
 * (e.g. {@code "extends"} vs {@code "implements"}) is carried on
 * {@link RawEdge#dependencyType()} because a single {@code SUPERTYPE}
 * category cannot distinguish the two Java forms.
 *
 * <p>{@code CROSS_FORMAT} is intentionally absent — cross-format linking is
 * outside the seam (ADR-0001 sub-decision 4).
 */
public enum EdgeKind {
    IMPORT,
    SUPERTYPE,
    EMBED
}
